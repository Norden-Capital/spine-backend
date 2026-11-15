package com.nordencapital.spine;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.passkey.FakeAuthenticator;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Face ID unlock of the wallet with the person's passkey (real WebAuthn data and signatures from
 * a software authenticator). Same effect as a correct PIN; a failure is not a wrong PIN.
 */
class WalletPasskeyUnlockIntegrationTests extends IntegrationTestBase {

	private static final String ORIGIN = "http://localhost:3000";

	private static final String PIN = "123456";

	@Test
	void statusSaysIfIHaveAPasskey() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.passkey").isEqualTo(false);
		register(a, new FakeAuthenticator(ORIGIN));
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.passkey").isEqualTo(true);
		// Also before a PIN exists, and only my own passkeys count.
		activeUser("bob@example.com", Role.MEMBER);
		assertThat(loggedIn("bob@example.com").get("/api/wallet/status")).bodyJson()
			.extractingPath("$.passkey")
			.isEqualTo(false);
	}

	@Test
	void optionsListOnlyMyPasskeysAndNeedUserVerification() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(a, phone);
		register(loggedIn("bob@example.com"), new FakeAuthenticator(ORIGIN));

		// No PIN yet.
		assertError(a.post("/api/wallet/unlock/passkey/options", ""), HttpStatus.CONFLICT, "no_pin");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);

		MvcTestResult options = a.post("/api/wallet/unlock/passkey/options", "");
		assertThat(options).hasStatus(HttpStatus.OK);
		assertThat(options).bodyJson().extractingPath("$.rpId").isEqualTo("localhost");
		assertThat(options).bodyJson().extractingPath("$.challenge").isNotNull();
		assertThat(options).bodyJson().extractingPath("$.userVerification").isEqualTo("required");
		assertThat(options).bodyJson().extractingPath("$.allowCredentials[*].id")
			.asArray()
			.containsExactly(phone.credentialIdBase64());
		assertThat(options).bodyJson().doesNotHavePath("$.publicKey");

		// Not for someone logged out.
		assertThat(client().post("/api/wallet/unlock/passkey/options", "")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void myPasskeyUnlocksLikeACorrectPin() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(a, phone);
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		a.post("/api/wallet/lock", "");
		assertError(a.get("/api/wallet/dashboard"), HttpStatus.LOCKED, "wallet_locked");
		// Two wrong PINs first: a good unlock sets the count back to 0, like a correct PIN.
		a.post("/api/wallet/unlock", "{\"pin\":\"000000\"}");
		a.post("/api/wallet/unlock", "{\"pin\":\"000000\"}");

		String options = options(a);
		assertThat(a.post("/api/wallet/unlock/passkey", body(phone.login(options)))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.unlocked").isEqualTo(true);
		assertThat(a.get("/api/wallet/dashboard")).hasStatus(HttpStatus.OK);
		assertThat(this.jdbc.queryForObject("SELECT failed_attempts FROM wallet_settings", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT sign_count FROM passkeys", Long.class)).isEqualTo(1);
		// Not a login: no login history.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE success", Integer.class))
			.isEqualTo(1);

		// The same answer cannot be used again.
		a.post("/api/wallet/lock", "");
		assertError(a.post("/api/wallet/unlock/passkey", body(phone.login(options))), HttpStatus.BAD_REQUEST,
				"passkey_failed");
		assertError(a.get("/api/wallet/dashboard"), HttpStatus.LOCKED, "wallet_locked");
	}

	@Test
	void aBadOrForeignPasskeyFailsAndIsNotAWrongPin() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		FakeAuthenticator phone = new FakeAuthenticator(ORIGIN);
		register(a, phone);
		FakeAuthenticator bobsPhone = new FakeAuthenticator(ORIGIN);
		register(loggedIn("bob@example.com"), bobsPhone);
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		a.post("/api/wallet/lock", "");

		// Broken signature.
		assertError(a.post("/api/wallet/unlock/passkey", body(phone.login(options(a), true))), HttpStatus.BAD_REQUEST,
				"passkey_failed");
		// Someone else's passkey.
		assertError(a.post("/api/wallet/unlock/passkey", body(bobsPhone.login(options(a)))), HttpStatus.BAD_REQUEST,
				"passkey_failed");
		// No options asked first.
		assertError(a.post("/api/wallet/unlock/passkey", body(phone.login(options(loggedIn("ada@example.com"))))),
				HttpStatus.BAD_REQUEST, "passkey_failed");
		// Options of a login ceremony do not unlock the wallet.
		MvcTestResult loginOptions = a.post("/api/auth/passkey/options", "");
		assertError(a.post("/api/wallet/unlock/passkey",
				body(phone.login(loginOptions.getResponse().getContentAsString()))), HttpStatus.BAD_REQUEST,
				"passkey_failed");
		// Garbage, and no body.
		options(a);
		assertError(a.post("/api/wallet/unlock/passkey", "{\"credential\":{\"id\":\"x\"}}"), HttpStatus.BAD_REQUEST,
				"passkey_failed");
		assertThat(a.post("/api/wallet/unlock/passkey", "{}")).hasStatus(HttpStatus.BAD_REQUEST);

		assertError(a.get("/api/wallet/dashboard"), HttpStatus.LOCKED, "wallet_locked");
		assertThat(this.jdbc.queryForObject("SELECT failed_attempts FROM wallet_settings", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT locked_until FROM wallet_settings", Object.class)).isNull();
		// The PIN still works.
		assertThat(a.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
	}

	// ---- helpers ----

	private static void register(Client client, FakeAuthenticator authenticator) throws Exception {
		MvcTestResult options = client.post("/api/me/passkeys/register/options", "");
		assertThat(options).hasStatus(HttpStatus.OK);
		String answer = authenticator.register(options.getResponse().getContentAsString());
		assertThat(client.post("/api/me/passkeys/register", "{\"name\":null,\"credential\":" + answer + "}"))
			.hasStatus(HttpStatus.CREATED);
	}

	private static String options(Client client) throws Exception {
		MvcTestResult result = client.post("/api/wallet/unlock/passkey/options", "");
		assertThat(result).hasStatus(HttpStatus.OK);
		return result.getResponse().getContentAsString();
	}

	private static String body(String credentialJson) {
		return "{\"credential\":" + credentialJson + "}";
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String error) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(error);
	}

}
