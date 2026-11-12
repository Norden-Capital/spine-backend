package com.nordencapital.spine;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.auth.AuthTokenService;
import com.nordencapital.spine.auth.TokenPurpose;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIntegrationTests extends IntegrationTestBase {

	@Autowired
	private AuthTokenService tokens;

	@Test
	void flywayRanAllMigrations() {
		assertThat(this.jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
				String.class))
			.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
	}

	@Test
	void healthIsPublic() {
		assertThat(client().get("/actuator/health")).hasStatus(HttpStatus.OK);
	}

	// ----- login -----

	@Test
	void loginStartsServerSideSession() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = client();

		MvcTestResult login = client.login("emp@example.com", PASSWORD);
		assertThat(login).hasStatus(HttpStatus.OK);
		assertThat(login).bodyJson().extractingPath("$.id").isEqualTo(user.getId().toString());
		assertThat(login).bodyJson().extractingPath("$.email").isEqualTo("emp@example.com");
		assertThat(login).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");

		String setCookie = String.join("\n", login.getResponse().getHeaders("Set-Cookie"));
		assertThat(setCookie).contains("SESSION=").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		assertThat(sessionCount(user.getId())).isEqualTo(1);
		assertThat(reload(user.getId()).getLastLoginAt()).isNotNull();

		assertThat(client.get("/api/auth/me")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.email")
			.isEqualTo("emp@example.com");
	}

	@Test
	void loginIgnoresEmailCaseAndSpaces() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		assertThat(client().login("  Emp@Example.COM ", PASSWORD)).hasStatus(HttpStatus.OK);
	}

	@Test
	void loginErrorIsTheSameForEveryKindOfBadLogin() {
		activeUser("active@example.com", Role.EMPLOYEE);
		invitedUser("invited@example.com", Role.EMPLOYEE);
		disabledUser("disabled@example.com", Role.EMPLOYEE);

		List<MvcTestResult> results = List.of(client().login("active@example.com", "wrong-password-123"),
				client().login("nobody@example.com", PASSWORD), client().login("invited@example.com", PASSWORD),
				client().login("disabled@example.com", PASSWORD));
		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
			assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("invalid_credentials");
			assertThat(result).bodyJson().extractingPath("$.message").isEqualTo("Invalid email or password");
		}
	}

	@Test
	void loginIsBlockedAfterFiveFailures() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = client();
		for (int i = 0; i < 5; i++) {
			assertThat(client.login("emp@example.com", "wrong-password-123")).hasStatus(HttpStatus.UNAUTHORIZED);
		}
		// Even the right password is refused while blocked.
		assertThat(client.login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("too_many_attempts");
	}

	@Test
	void logoutDeletesSession() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		assertThat(client.post("/api/auth/logout", "")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(client.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessionCount(user.getId())).isZero();
	}

	@Test
	void stolenOldSessionCookieStopsWorkingAfterLogout() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		String sessionCookie = client.cookie("SESSION");
		client.post("/api/auth/logout", "");

		assertThat(this.mvc.get()
			.uri("/api/auth/me")
			.cookie(new jakarta.servlet.http.Cookie("SESSION", sessionCookie))).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	// ----- forgot password -----

	@Test
	void forgotPasswordAlwaysReturns202ButOnlyMailsActiveUsers() {
		activeUser("active@example.com", Role.EMPLOYEE);
		invitedUser("invited@example.com", Role.EMPLOYEE);
		disabledUser("disabled@example.com", Role.EMPLOYEE);

		for (String email : List.of("active@example.com", "invited@example.com", "disabled@example.com",
				"nobody@example.com")) {
			assertThat(client().post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}"))
				.hasStatus(HttpStatus.ACCEPTED);
		}
		assertThat(this.mails.all()).extracting((m) -> m.to()).containsExactly("active@example.com");
	}

	@Test
	void forgotPasswordIsLimitedButStillReturns202() {
		activeUser("active@example.com", Role.EMPLOYEE);
		Client client = client();
		for (int i = 0; i < 5; i++) {
			assertThat(client.post("/api/auth/forgot-password", "{\"email\":\"active@example.com\"}"))
				.hasStatus(HttpStatus.ACCEPTED);
		}
		assertThat(this.mails.to("active@example.com")).hasSize(3);
	}

	@Test
	void resetPasswordFlow() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		client().post("/api/auth/forgot-password", "{\"email\":\"emp@example.com\"}");
		String token = tokenFromLastMailTo("emp@example.com");

		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isEqualTo("{\"email\":\"emp@example.com\",\"purpose\":\"RESET\"}");

		Client client = client();
		assertThat(client.post("/api/auth/set-password",
				"{\"token\":\"" + token + "\",\"password\":\"a-brand-new-password\"}"))
			.hasStatus(HttpStatus.NO_CONTENT);
		// Does not log in.
		assertThat(client.cookie("SESSION")).isNull();

		assertThat(client().login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("emp@example.com", "a-brand-new-password")).hasStatus(HttpStatus.OK);
		assertThat(reload(user.getId()).getPasswordHash()).doesNotContain("a-brand-new-password");

		// One-time use.
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.GONE);
		assertThat(client().post("/api/auth/set-password",
				"{\"token\":\"" + token + "\",\"password\":\"another-new-password\"}"))
			.hasStatus(HttpStatus.GONE)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("token_invalid");
	}

	@Test
	void passwordResetLogsOutOtherSessions() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		Client oldBrowser = loggedIn("emp@example.com");
		String token = this.tokens.issue(user, TokenPurpose.RESET);

		client().post("/api/auth/set-password", "{\"token\":\"" + token + "\",\"password\":\"a-brand-new-password\"}");

		assertThat(oldBrowser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	// ----- invite tokens -----

	@Test
	void inviteFlowActivatesUserWithoutLoggingIn() {
		User user = invitedUser("new@example.com", Role.EMPLOYEE);
		String token = this.tokens.issue(user, TokenPurpose.INVITE);

		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isEqualTo("{\"email\":\"new@example.com\",\"purpose\":\"INVITE\"}");

		// Weak password: refused, and the token still works.
		assertThat(client().post("/api/auth/set-password", "{\"token\":\"" + token + "\",\"password\":\"short\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("weak_password");
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK);

		Client client = client();
		assertThat(client.post("/api/auth/set-password", "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}"))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(reload(user.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(client.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("new@example.com", PASSWORD)).hasStatus(HttpStatus.OK);
	}

	@Test
	void expiredTokenIsInvalid() {
		User user = invitedUser("new@example.com", Role.EMPLOYEE);
		String token = this.tokens.issue(user, TokenPurpose.INVITE);
		expireAllTokens();

		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.GONE);
		assertThat(client().post("/api/auth/set-password", "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}"))
			.hasStatus(HttpStatus.GONE);
		assertThat(reload(user.getId()).getStatus()).isEqualTo(UserStatus.INVITED);
	}

	@Test
	void newTokenMakesOlderOneInvalid() {
		User user = invitedUser("new@example.com", Role.EMPLOYEE);
		String first = this.tokens.issue(user, TokenPurpose.INVITE);
		String second = this.tokens.issue(user, TokenPurpose.INVITE);

		assertThat(client().get("/api/auth/token/" + first)).hasStatus(HttpStatus.GONE);
		assertThat(client().get("/api/auth/token/" + second)).hasStatus(HttpStatus.OK);
	}

	@Test
	void tokenOnlyStoredAsHash() {
		User user = invitedUser("new@example.com", Role.EMPLOYEE);
		String token = this.tokens.issue(user, TokenPurpose.INVITE);
		String stored = this.jdbc.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id = ?", String.class,
				user.getId());
		assertThat(stored).hasSize(64).isNotEqualTo(token).doesNotContain(token);
	}

	@Test
	void unknownTokenIsInvalid() {
		assertThat(client().get("/api/auth/token/not-a-real-token")).hasStatus(HttpStatus.GONE)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("token_invalid");
	}

	@Test
	void inviteTokenStopsWorkingWhenUserIsDisabled() {
		User user = invitedUser("new@example.com", Role.EMPLOYEE);
		String token = this.tokens.issue(user, TokenPurpose.INVITE);
		this.transaction.executeWithoutResult((status) -> {
			User managed = this.users.findById(user.getId()).orElseThrow();
			managed.setStatus(UserStatus.DISABLED, java.time.Instant.now());
		});
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.GONE);
	}

}
