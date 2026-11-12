package com.nordencapital.spine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.nordencapital.spine.auth.AuthLimiters;
import com.nordencapital.spine.mail.MailMessage;
import com.nordencapital.spine.mail.MailSender;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;
import com.nordencapital.spine.user.UserStatus;

/**
 * Full app + real Postgres (Testcontainers, needs Docker). Emails are captured instead of
 * sent. Each test starts with empty users, tokens and sessions.
 */
@SpringBootTest(properties = { "app.bootstrap.admin-email=bootstrap@example.com", "app.files.dir=target/test-files" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, IntegrationTestBase.MailCapture.class })
public abstract class IntegrationTestBase {

	protected static final String PASSWORD = "correct-horse-battery";

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("set-password\\?token=([A-Za-z0-9_-]+)");

	@Autowired
	protected MockMvcTester mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected UserRepository users;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	@Autowired
	protected TransactionTemplate transaction;

	@Autowired
	protected AuthLimiters limiters;

	@Autowired
	protected CapturingMailSender mails;

	@BeforeEach
	void cleanDatabase() throws IOException {
		this.jdbc.update("DELETE FROM notifications");
		this.jdbc.update("DELETE FROM wallet_goal_contributions");
		this.jdbc.update("DELETE FROM wallet_goals");
		this.jdbc.update("DELETE FROM wallet_budgets");
		this.jdbc.update("DELETE FROM files");
		this.jdbc.update("DELETE FROM wallet_transactions");
		this.jdbc.update("DELETE FROM wallet_recurring");
		this.jdbc.update("DELETE FROM wallet_tags");
		this.jdbc.update("DELETE FROM wallet_categories");
		this.jdbc.update("DELETE FROM wallet_settings");
		this.jdbc.update("DELETE FROM transaction_comments");
		this.jdbc.update("DELETE FROM transaction_history");
		this.jdbc.update("DELETE FROM transaction_tags");
		this.jdbc.update("DELETE FROM files");
		this.jdbc.update("DELETE FROM transactions");
		this.jdbc.update("DELETE FROM tags");
		this.jdbc.update("DELETE FROM categories");
		// Back to the 7 starting categories, with the same SQL as the migration.
		this.jdbc.execute(new ClassPathResource("db/migration/V9__seed_categories.sql")
			.getContentAsString(StandardCharsets.UTF_8));
		this.jdbc.update("DELETE FROM spring_session");
		this.jdbc.update("DELETE FROM auth_tokens");
		this.jdbc.update("DELETE FROM login_events");
		this.jdbc.update("DELETE FROM user_preferences");
		this.jdbc.update("DELETE FROM users");
		this.limiters.clearAll();
		this.mails.clear();
	}

	protected User activeUser(String email, Role role) {
		return this.transaction.execute((status) -> {
			User user = User.invite(email, role, Instant.now());
			user.setPasswordHash(this.passwordEncoder.encode(PASSWORD), Instant.now());
			user.setStatus(UserStatus.ACTIVE, Instant.now());
			return this.users.save(user);
		});
	}

	protected User invitedUser(String email, Role role) {
		return this.transaction.execute((status) -> this.users.save(User.invite(email, role, Instant.now())));
	}

	protected User disabledUser(String email, Role role) {
		return this.transaction.execute((status) -> {
			User user = User.invite(email, role, Instant.now());
			user.setPasswordHash(this.passwordEncoder.encode(PASSWORD), Instant.now());
			user.setStatus(UserStatus.DISABLED, Instant.now());
			return this.users.save(user);
		});
	}

	protected User reload(UUID id) {
		return this.users.findById(id).orElseThrow();
	}

	protected int sessionCount(UUID userId) {
		return this.jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class,
				userId.toString());
	}

	/** Token from the link in the last email sent to this address. */
	protected String tokenFromLastMailTo(String email) {
		List<MailMessage> sent = this.mails.to(email);
		if (sent.isEmpty()) {
			throw new AssertionError("No email sent to " + email);
		}
		Matcher matcher = TOKEN_IN_LINK.matcher(sent.get(sent.size() - 1).html());
		if (!matcher.find()) {
			throw new AssertionError("No set-password link in email to " + email);
		}
		return matcher.group(1);
	}

	protected void expireAllTokens() {
		this.jdbc.update("UPDATE auth_tokens SET expires_at = now() - interval '1 minute'");
	}

	protected Client client() {
		return new Client(this.mvc);
	}

	protected Client loggedIn(String email) {
		Client client = client();
		MvcTestResult result = client.login(email, PASSWORD);
		if (result.getResponse().getStatus() != 200) {
			throw new AssertionError("Login failed for " + email + ": " + result.getResponse().getStatus());
		}
		return client;
	}

	/**
	 * A browser-like client: keeps cookies and sends the XSRF-TOKEN cookie back as the
	 * X-XSRF-TOKEN header, like the frontend does.
	 */
	public static final class Client {

		private final MockMvcTester mvc;

		private final Map<String, String> cookies = new LinkedHashMap<>();

		Client(MockMvcTester mvc) {
			this.mvc = mvc;
		}

		public MvcTestResult get(String uri) {
			return send(this.mvc.get().uri(uri));
		}

		public MvcTestResult post(String uri, String json) {
			return sendWithCsrf(this.mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		public MvcTestResult patch(String uri, String json) {
			return sendWithCsrf(this.mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		public MvcTestResult put(String uri, String json) {
			return sendWithCsrf(this.mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		public MvcTestResult delete(String uri) {
			return sendWithCsrf(this.mvc.delete().uri(uri));
		}

		/** Uploads a file to POST /api/files (multipart, field "file"). */
		public MvcTestResult upload(String name, String contentType, byte[] content) {
			if (!this.cookies.containsKey("XSRF-TOKEN")) {
				get("/api/auth/csrf");
			}
			var request = this.mvc.post()
				.uri("/api/files")
				.multipart()
				.file(new MockMultipartFile("file", name, contentType, content))
				.header("X-XSRF-TOKEN", this.cookies.get("XSRF-TOKEN"));
			if (!this.cookies.isEmpty()) {
				request.cookie(this.cookies.entrySet()
					.stream()
					.map((entry) -> new Cookie(entry.getKey(), entry.getValue()))
					.toArray(Cookie[]::new));
			}
			return keepCookies(request.exchange());
		}

		/** Same as {@link #post} but without the X-XSRF-TOKEN header. */
		public MvcTestResult postWithoutCsrf(String uri, String json) {
			return send(this.mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		/** Same as {@link #put} but without the X-XSRF-TOKEN header. */
		public MvcTestResult putWithoutCsrf(String uri, String json) {
			return send(this.mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		public MvcTestResult login(String email, String password) {
			return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
		}

		public String cookie(String name) {
			return this.cookies.get(name);
		}

		private MvcTestResult sendWithCsrf(MockMvcRequestBuilder request) {
			if (!this.cookies.containsKey("XSRF-TOKEN")) {
				get("/api/auth/csrf");
			}
			return send(request.header("X-XSRF-TOKEN", this.cookies.get("XSRF-TOKEN")));
		}

		private MvcTestResult send(MockMvcRequestBuilder request) {
			if (!this.cookies.isEmpty()) {
				request.cookie(this.cookies.entrySet()
					.stream()
					.map((entry) -> new Cookie(entry.getKey(), entry.getValue()))
					.toArray(Cookie[]::new));
			}
			return keepCookies(request.exchange());
		}

		private MvcTestResult keepCookies(MvcTestResult result) {
			for (Cookie cookie : result.getResponse().getCookies()) {
				if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) {
					this.cookies.remove(cookie.getName());
				}
				else {
					this.cookies.put(cookie.getName(), cookie.getValue());
				}
			}
			return result;
		}

	}

	public static final class CapturingMailSender implements MailSender {

		private final List<MailMessage> sent = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void send(MailMessage message) {
			this.sent.add(message);
		}

		public List<MailMessage> all() {
			return List.copyOf(this.sent);
		}

		public List<MailMessage> to(String email) {
			return all().stream().filter((message) -> message.to().equals(email)).toList();
		}

		public void clear() {
			this.sent.clear();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MailCapture {

		@Bean
		@Primary
		CapturingMailSender capturingMailSender() {
			return new CapturingMailSender();
		}

	}

}
