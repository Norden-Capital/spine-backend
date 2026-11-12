package com.nordencapital.spine;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.passkey.FakeAuthenticator;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Passkeys end to end with a software authenticator (real WebAuthn data and signatures).
 */
class PasskeyIntegrationTests extends IntegrationTestBase {

	private static final String ORIGIN = "http://localhost:3000";

	@Test
	void registrationOptionsAskForAResidentKeyAndUserVerification() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		MvcTestResult options = loggedIn("emp@example.com").post("/api/me/passkeys/register/options", "");
		assertThat(options).hasStatus(HttpStatus.OK);
		assertThat(options).bodyJson().extractingPath("$.rp.id").isEqualTo("localhost");
		assertThat(options).bodyJson().extractingPath("$.rp.name").isEqualTo("Spine");
		assertThat(options).bodyJson().extractingPath("$.user.name").isEqualTo("emp@example.com");
		assertThat(options).bodyJson().extractingPath("$.authenticatorSelection.residentKey").isEqualTo("required");
		assertThat(options).bodyJson()
			.extractingPath("$.authenticatorSelection.userVerification")
			.isEqualTo("required");
		assertThat(options).bodyJson().extractingPath("$.challenge").isNotNull();
		// The browser API wants the inner options, not {"publicKey": {...}}.
		assertThat(options).bodyJson().doesNotHavePath("$.publicKey");
	}

	@Test
	void addListRenameDeleteMyPasskey() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);

		MvcTestResult added = register(client, phone, "  My iPhone ");
		assertThat(added).hasStatus(HttpStatus.CREATED);
		assertThat(added).bodyJson().extractingPath("$.name").isEqualTo("My iPhone");
		assertThat(added).bodyJson().extractingPath("$.createdAt").isNotNull();
		assertThat(added).bodyJson().extractingPath("$.lastUsedAt").isNull();
		String id = this.jdbc.queryForObject("SELECT id FROM passkeys", String.class);

		MvcTestResult list = client.get("/api/me/passkeys");
		assertThat(list).bodyJson().extractingPath("$[*].name").asArray().containsExactly("My iPhone");
		assertThat(list.getResponse().getContentAsString()).doesNotContain("publicKey").doesNotContain("credential");
		assertThat(this.jdbc.queryForObject("SELECT transports FROM passkeys", String.class)).contains("internal");

		assertThat(client.patch("/api/me/passkeys/" + id, "{\"name\":\"Work phone\"}")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo("Work phone");
		assertThat(client.patch("/api/me/passkeys/" + id, "{\"name\":\"  \"}")).hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(client.delete("/api/me/passkeys/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(client.get("/api/me/passkeys")).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	@Test
	void defaultNameIsPasskey() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		assertThat(register(client, new FakeAuthenticator(ORIGIN), null)).bodyJson()
			.extractingPath("$.name")
			.isEqualTo("Passkey");
	}

	@Test
	void registrationNeedsFreshOptionsAndTheRightOrigin() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		// No options asked first.
		String answer = new FakeAuthenticator(ORIGIN).register(options(client, "/api/me/passkeys/register/options"));
		Client other = loggedIn("emp@example.com");
		assertPasskeyInvalid(other.post("/api/me/passkeys/register", registerBody(null, answer)),
				HttpStatus.BAD_REQUEST);

		// Options can be used once only.
		assertThat(client.post("/api/me/passkeys/register", registerBody(null, answer)))
			.hasStatus(HttpStatus.CREATED);
		assertPasskeyInvalid(client.post("/api/me/passkeys/register", registerBody(null, answer)),
				HttpStatus.BAD_REQUEST);

		// Another website cannot add a passkey for us.
		FakeAuthenticator phishing = new FakeAuthenticator("https://evil.example.com");
		String evil = phishing.register(options(client, "/api/me/passkeys/register/options"));
		assertPasskeyInvalid(client.post("/api/me/passkeys/register", registerBody(null, evil)),
				HttpStatus.BAD_REQUEST);

		// Any localhost port is fine locally (frontend on 3001).
		FakeAuthenticator otherPort = new FakeAuthenticator("http://localhost:3001");
		String fromOtherPort = otherPort.register(options(client, "/api/me/passkeys/register/options"));
		assertThat(client.post("/api/me/passkeys/register", registerBody(null, fromOtherPort)))
			.hasStatus(HttpStatus.CREATED);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM passkeys", Integer.class)).isEqualTo(2);
	}

	@Test
	void loginWithPasskeyStartsANormalSession() throws Exception {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(loggedIn("emp@example.com"), phone, "iPhone");
		this.jdbc.update("DELETE FROM login_events");

		Client browser = client();
		String request = options(browser, "/api/auth/passkey/options");
		String sessionBefore = browser.cookie("SESSION");
		String csrfBefore = browser.cookie("XSRF-TOKEN");
		assertThat(request).doesNotContain("allowCredentials\":[{");

		MvcTestResult login = browser.post("/api/auth/passkey/login", loginBody(phone.login(request)));
		assertThat(login).hasStatus(HttpStatus.OK);
		assertThat(login).bodyJson().extractingPath("$.id").isEqualTo(user.getId().toString());
		assertThat(login).bodyJson().extractingPath("$.email").isEqualTo("emp@example.com");
		assertThat(login).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");
		assertThat(login).bodyJson().extractingPath("$.firstName").isNull();
		assertThat(browser.cookie("SESSION")).isNotNull().isNotEqualTo(sessionBefore);
		assertThat(browser.cookie("XSRF-TOKEN")).isNotNull().isNotEqualTo(csrfBefore);

		assertThat(browser.get("/api/me")).hasStatus(HttpStatus.OK);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE success", Integer.class))
			.isEqualTo(1);
		assertThat(reload(user.getId()).getLastLoginAt()).isNotNull();
		assertThat(this.jdbc.queryForObject("SELECT sign_count FROM passkeys", Long.class)).isEqualTo(1);
		assertThat(browser.get("/api/me/passkeys")).bodyJson().extractingPath("$[0].lastUsedAt").isNotNull();

		// The same answer cannot be used again.
		Client replay = client();
		options(replay, "/api/auth/passkey/options");
		assertPasskeyInvalid(replay.post("/api/auth/passkey/login", loginBody(phone.login(request))),
				HttpStatus.UNAUTHORIZED);
	}

	@Test
	void currentMarksOnlyThePasskeyUsedForThisSession() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		FakeAuthenticator laptop = new FakeAuthenticator(ORIGIN);
		Client byPassword = loggedIn("emp@example.com");
		register(byPassword, phone, "iPhone");
		register(byPassword, laptop, "Laptop");

		// Password login: no passkey is "this device".
		assertThat(byPassword.get("/api/me/passkeys")).bodyJson()
			.extractingPath("$[*].current")
			.asArray()
			.containsExactly(false, false);

		Client browser = client();
		String request = options(browser, "/api/auth/passkey/options");
		assertThat(browser.post("/api/auth/passkey/login", loginBody(phone.login(request)))).hasStatus(HttpStatus.OK);
		MvcTestResult list = browser.get("/api/me/passkeys");
		assertThat(list).bodyJson().extractingPath("$[?(@.name == 'iPhone')].current").asArray().containsExactly(true);
		assertThat(list).bodyJson().extractingPath("$[?(@.name == 'Laptop')].current").asArray().containsExactly(false);

		// Logging in again in the same browser with a password clears it.
		assertThat(browser.post("/api/auth/login",
				"{\"email\":\"emp@example.com\",\"password\":\"" + PASSWORD + "\"}")).hasStatus(HttpStatus.OK);
		assertThat(browser.get("/api/me/passkeys")).bodyJson()
			.extractingPath("$[*].current")
			.asArray()
			.containsExactly(false, false);
	}

	@Test
	void disabledUserCannotLogInWithPasskey() throws Exception {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(loggedIn("emp@example.com"), phone, "iPhone");
		this.jdbc.update("DELETE FROM login_events");
		this.jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user.getId());

		Client browser = client();
		String request = options(browser, "/api/auth/passkey/options");
		assertPasskeyInvalid(browser.post("/api/auth/passkey/login", loginBody(phone.login(request))),
				HttpStatus.UNAUTHORIZED);
		assertThat(browser.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE NOT success", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void badSignatureOrUnknownPasskeyIsRefused() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(loggedIn("emp@example.com"), phone, "iPhone");
		this.jdbc.update("DELETE FROM login_events");

		Client browser = client();
		String request = options(browser, "/api/auth/passkey/options");
		assertPasskeyInvalid(browser.post("/api/auth/passkey/login", loginBody(phone.login(request, true))),
				HttpStatus.UNAUTHORIZED);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE NOT success", Integer.class))
			.isEqualTo(1);

		// A passkey that was never added: refused, nothing saved (unknown user).
		FakeAuthenticator stranger = new FakeAuthenticator(ORIGIN);
		Client strangerAdds = loggedIn("emp@example.com");
		stranger.register(options(strangerAdds, "/api/me/passkeys/register/options"));
		String request2 = options(browser, "/api/auth/passkey/options");
		assertPasskeyInvalid(browser.post("/api/auth/passkey/login", loginBody(stranger.login(request2))),
				HttpStatus.UNAUTHORIZED);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE NOT success", Integer.class))
			.isEqualTo(1);

		// Garbage.
		options(browser, "/api/auth/passkey/options");
		assertPasskeyInvalid(browser.post("/api/auth/passkey/login", "{\"credential\":{\"id\":\"x\"}}"),
				HttpStatus.UNAUTHORIZED);
		assertThat(browser.post("/api/auth/passkey/login", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void tooManyFailedPasskeyLoginsAreBlocked() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(loggedIn("emp@example.com"), phone, "iPhone");

		Client browser = client();
		for (int i = 0; i < 5; i++) {
			String request = options(browser, "/api/auth/passkey/options");
			assertPasskeyInvalid(browser.post("/api/auth/passkey/login", loginBody(phone.login(request, true))),
					HttpStatus.UNAUTHORIZED);
		}
		String request = options(browser, "/api/auth/passkey/options");
		MvcTestResult blocked = browser.post("/api/auth/passkey/login", loginBody(phone.login(request)));
		assertThat(blocked).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(blocked).bodyJson().extractingPath("$.error").isEqualTo("too_many_attempts");
	}

	@Test
	void passkeysAreOwnDataOnly() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		activeUser("other@example.com", Role.EMPLOYEE);
		register(loggedIn("emp@example.com"), new FakeAuthenticator(ORIGIN), "Mine");
		String id = this.jdbc.queryForObject("SELECT id FROM passkeys", String.class);
		Client other = loggedIn("other@example.com");

		assertThat(other.get("/api/me/passkeys")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertNotFound(other.patch("/api/me/passkeys/" + id, "{\"name\":\"Stolen\"}"));
		assertNotFound(other.delete("/api/me/passkeys/" + id));
		assertNotFound(other.delete("/api/me/passkeys/" + UUID.randomUUID()));
		assertThat(this.jdbc.queryForObject("SELECT name FROM passkeys", String.class)).isEqualTo("Mine");
	}

	@Test
	void anonymousAndCsrfRules() {
		assertThat(client().get("/api/me/passkeys")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().post("/api/me/passkeys/register/options", "")).hasStatus(HttpStatus.UNAUTHORIZED);
		MvcTestResult noCsrf = client().postWithoutCsrf("/api/auth/passkey/options", "");
		assertThat(noCsrf).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(noCsrf).bodyJson().extractingPath("$.error").isEqualTo("csrf_invalid");
	}

	@Test
	void adminSeesOnlyThePasskeyCount() throws Exception {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client emp = loggedIn("emp@example.com");
		register(emp, new FakeAuthenticator(ORIGIN), "Phone");
		register(emp, new FakeAuthenticator(ORIGIN), "Laptop");
		Client admin = loggedIn("admin@example.com");

		MvcTestResult detail = admin.get("/api/admin/users/" + employee.getId());
		assertThat(detail).bodyJson().extractingPath("$.passkeyCount").isEqualTo(2);
		assertThat(detail.getResponse().getContentAsString()).doesNotContain("Phone");
		assertThat(admin.get("/api/admin/users")).bodyJson().doesNotHavePath("$[0].passkeyCount");

		// Disable still works and blocks the passkey session too.
		assertThat(admin.post("/api/admin/users/" + employee.getId() + "/disable", "")).hasStatus(HttpStatus.OK);
		assertThat(emp.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	private MvcTestResult register(Client client, FakeAuthenticator authenticator, String name) throws Exception {
		String answer = authenticator.register(options(client, "/api/me/passkeys/register/options"));
		return client.post("/api/me/passkeys/register", registerBody(name, answer));
	}

	private static String options(Client client, String uri) {
		MvcTestResult result = client.post(uri, "");
		assertThat(result).hasStatus(HttpStatus.OK);
		try {
			return result.getResponse().getContentAsString();
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String registerBody(String name, String credentialJson) {
		String nameJson = (name != null) ? "\"" + name + "\"" : "null";
		return "{\"name\":" + nameJson + ",\"credential\":" + credentialJson + "}";
	}

	private static String loginBody(String credentialJson) {
		return "{\"credential\":" + credentialJson + "}";
	}

	private static void assertPasskeyInvalid(MvcTestResult result, HttpStatus status) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("passkey_invalid");
	}

	private static void assertNotFound(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("passkey_not_found");
	}

}
