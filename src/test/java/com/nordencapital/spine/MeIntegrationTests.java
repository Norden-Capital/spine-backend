package com.nordencapital.spine;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/me: profile, names, login history, password change, preferences. Plus the login
 * history written by POST /api/auth/login.
 */
class MeIntegrationTests extends IntegrationTestBase {

	private static final String NEW_PASSWORD = "a-brand-new-password";

	private static final String TORONTO_PREFS = "{\"dateFormat\":\"YYYY-MM-DD\",\"timeZone\":\"America/Toronto\","
			+ "\"theme\":\"DARK\",\"font\":\"INTER\",\"textSize\":\"LARGE\"}";

	@Test
	void everyEndpointNeedsLogin() {
		Client anonymous = client();
		assertThat(anonymous.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(anonymous.get("/api/me/sign-ins")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(anonymous.get("/api/me/preferences")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(anonymous.patch("/api/me", "{\"firstName\":\"A\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(anonymous.put("/api/me/preferences", TORONTO_PREFS)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(anonymous.post("/api/me/password",
				"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void writesNeedCsrfToken() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		MvcTestResult result = loggedIn("emp@example.com").putWithoutCsrf("/api/me/preferences", TORONTO_PREFS);
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("csrf_invalid");
	}

	@Test
	void profileShowsOwnData() throws Exception {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		activeUser("other@example.com", Role.EMPLOYEE);

		MvcTestResult result = loggedIn("emp@example.com").get("/api/me");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(user.getId().toString());
		assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("emp@example.com");
		assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
		assertThat(result).bodyJson().extractingPath("$.firstName").isNull();
		assertThat(result).bodyJson().extractingPath("$.lastName").isNull();
		assertThat(result).bodyJson().extractingPath("$.createdAt").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.lastLoginAt").isNotNull();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
	}

	@Test
	void namesAreTrimmedAndShownInAuthMe() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		User other = activeUser("other@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		MvcTestResult saved = client.patch("/api/me", "{\"firstName\":\"  Ada \",\"lastName\":\"   \"}");
		assertThat(saved).hasStatus(HttpStatus.OK);
		assertThat(saved).bodyJson().extractingPath("$.firstName").isEqualTo("Ada");
		assertThat(saved).bodyJson().extractingPath("$.lastName").isNull();
		assertThat(saved).bodyJson().extractingPath("$.email").isEqualTo("emp@example.com");

		MvcTestResult me = client.get("/api/auth/me");
		assertThat(me).bodyJson().extractingPath("$.firstName").isEqualTo("Ada");
		assertThat(me).bodyJson().extractingPath("$.lastName").isNull();
		assertThat(me).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");

		assertThat(reload(user.getId()).getFirstName()).isEqualTo("Ada");
		assertThat(reload(other.getId()).getFirstName()).isNull();
	}

	@Test
	void namesAreValidated() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		MvcTestResult tooLong = client.patch("/api/me", "{\"firstName\":\"" + "x".repeat(61) + "\",\"lastName\":null}");
		assertThat(tooLong).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(tooLong).bodyJson().extractingPath("$.error").isEqualTo("validation_error");

		MvcTestResult sixtyWithSpaces = client.patch("/api/me",
				"{\"firstName\":\"  " + "x".repeat(60) + "  \",\"lastName\":\"Lovelace\"}");
		assertThat(sixtyWithSpaces).hasStatus(HttpStatus.OK);

		MvcTestResult newline = client.patch("/api/me", "{\"firstName\":\"A\\nB\",\"lastName\":null}");
		assertThat(newline).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(newline).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
	}

	@Test
	void loginAttemptsOfKnownUsersAreRecorded() {
		User active = activeUser("active@example.com", Role.EMPLOYEE);
		User disabled = disabledUser("disabled@example.com", Role.EMPLOYEE);
		User invited = invitedUser("invited@example.com", Role.EMPLOYEE);

		assertThat(client().login("active@example.com", PASSWORD)).hasStatus(HttpStatus.OK);
		assertThat(client().login("active@example.com", "wrong-password-123")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("disabled@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("invited@example.com", "anything-at-all")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("nobody@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);

		assertThat(events(active.getId(), true)).isEqualTo(1);
		assertThat(events(active.getId(), false)).isEqualTo(1);
		assertThat(events(disabled.getId(), false)).isEqualTo(1);
		assertThat(events(invited.getId(), false)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM login_events", Integer.class)).isEqualTo(4);
		assertThat(this.jdbc.queryForObject("SELECT ip FROM login_events WHERE user_id = ? AND success",
				String.class, active.getId())).isNotBlank();
	}

	@Test
	void signInsAreGroupedByDayInMyTimeZone() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		User other = activeUser("other@example.com", Role.EMPLOYEE);
		// 03:00 UTC is 23:00 the day before in Toronto (UTC-4, summer time since March 8).
		insertEvent(user.getId(), "2026-03-10T03:00:00Z", true);
		insertEvent(user.getId(), "2026-03-10T12:00:00Z", false);
		insertEvent(user.getId(), "2026-03-10T13:00:00Z", true);
		insertEvent(other.getId(), "2026-03-10T12:00:00Z", false);
		Client client = loggedIn("emp@example.com");

		// No time zone saved: UTC.
		assertThat(client.get("/api/me/sign-ins?from=2026-03-01")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isStrictlyEqualTo("[{\"date\":\"2026-03-10\",\"ok\":2,\"failed\":1},"
					+ "{\"date\":\"" + LocalDate.now(ZoneOffset.UTC) + "\",\"ok\":1,\"failed\":0}]");

		assertThat(client.put("/api/me/preferences", TORONTO_PREFS)).hasStatus(HttpStatus.OK);
		assertThat(client.get("/api/me/sign-ins?from=2026-03-01")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isStrictlyEqualTo("[{\"date\":\"2026-03-09\",\"ok\":1,\"failed\":0},"
					+ "{\"date\":\"2026-03-10\",\"ok\":1,\"failed\":1},"
					+ "{\"date\":\"" + LocalDate.now(ZoneId.of("America/Toronto")) + "\",\"ok\":1,\"failed\":0}]");

		// "from" is a day in my time zone: 2026-03-10 starts at 04:00 UTC in Toronto.
		assertThat(client.get("/api/me/sign-ins?from=2026-03-10")).bodyJson()
			.extractingPath("$[0].date")
			.isEqualTo("2026-03-10");
	}

	@Test
	void signInsDefaultToTheLastSixMonths() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		insertEvent(user.getId(), Instant.now().minus(Duration.ofDays(200)).toString(), true);
		insertEvent(user.getId(), Instant.now().minus(Duration.ofDays(30)).toString(), false);
		Client client = loggedIn("emp@example.com");

		MvcTestResult result = client.get("/api/me/sign-ins");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(2);
		assertThat(result).bodyJson().extractingPath("$[0].failed").isEqualTo(1);
		assertThat(result).bodyJson().extractingPath("$[1].ok").isEqualTo(1);

		assertThat(client.get("/api/me/sign-ins?from=not-a-date")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void changePasswordKeepsThisSessionAndLogsOutTheOthers() {
		User user = activeUser("emp@example.com", Role.EMPLOYEE);
		Client here = loggedIn("emp@example.com");
		Client otherDevice = loggedIn("emp@example.com");
		assertThat(sessionCount(user.getId())).isEqualTo(2);

		assertThat(here.post("/api/me/password", changeBody(PASSWORD, NEW_PASSWORD)))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(here.get("/api/me")).hasStatus(HttpStatus.OK);
		assertThat(otherDevice.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessionCount(user.getId())).isEqualTo(1);
		assertThat(client().login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().login("emp@example.com", NEW_PASSWORD)).hasStatus(HttpStatus.OK);
	}

	@Test
	void changePasswordChecksCurrentAndNewPassword() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		MvcTestResult wrong = client.post("/api/me/password", changeBody("not-my-password", NEW_PASSWORD));
		assertThat(wrong).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(wrong).bodyJson().extractingPath("$.error").isEqualTo("invalid_current_password");

		MvcTestResult weak = client.post("/api/me/password", changeBody(PASSWORD, "short"));
		assertThat(weak).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(weak).bodyJson().extractingPath("$.error").isEqualTo("weak_password");

		MvcTestResult tooLong = client.post("/api/me/password", changeBody(PASSWORD, "x".repeat(129)));
		assertThat(tooLong).bodyJson().extractingPath("$.error").isEqualTo("weak_password");

		assertThat(client().login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.OK);
	}

	@Test
	void tooManyWrongCurrentPasswordsAreBlocked() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		for (int i = 0; i < 5; i++) {
			assertThat(client.post("/api/me/password", changeBody("wrong-password-" + i, NEW_PASSWORD)))
				.hasStatus(HttpStatus.BAD_REQUEST);
		}

		MvcTestResult blocked = client.post("/api/me/password", changeBody(PASSWORD, NEW_PASSWORD));
		assertThat(blocked).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(blocked).bodyJson().extractingPath("$.error").isEqualTo("too_many_attempts");
		assertThat(client().login("emp@example.com", NEW_PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void preferencesHaveDefaultsAndAreSavedPerUser() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		activeUser("other@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		String defaults = "{\"dateFormat\":\"DD/MM/YYYY\",\"timeZone\":null,\"theme\":\"SYSTEM\","
				+ "\"font\":\"GEIST\",\"textSize\":\"NORMAL\"}";

		assertThat(client.get("/api/me/preferences")).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo(defaults);

		assertThat(client.put("/api/me/preferences", TORONTO_PREFS)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isStrictlyEqualTo(TORONTO_PREFS);
		assertThat(client.get("/api/me/preferences")).bodyJson().isStrictlyEqualTo(TORONTO_PREFS);

		String auto = TORONTO_PREFS.replace("\"America/Toronto\"", "null");
		assertThat(client.put("/api/me/preferences", auto)).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo(auto);

		assertThat(loggedIn("other@example.com").get("/api/me/preferences")).bodyJson().isStrictlyEqualTo(defaults);
	}

	@Test
	void preferencesAreValidated() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("DARK", "PINK")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("YYYY-MM-DD", "DD.MM.YYYY")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("INTER", "COMIC")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("LARGE", "HUGE")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("America/Toronto", "Mars/Base")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("America/Toronto", "+05:00")));
		assertValidationError(client.put("/api/me/preferences", TORONTO_PREFS.replace("\"theme\":\"DARK\",", "")));

		assertThat(client.get("/api/me/preferences")).bodyJson().extractingPath("$.theme").isEqualTo("SYSTEM");
	}

	private static void assertValidationError(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
	}

	private static String changeBody(String currentPassword, String newPassword) {
		return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
	}

	private int events(UUID userId, boolean success) {
		return this.jdbc.queryForObject("SELECT count(*) FROM login_events WHERE user_id = ? AND success = ?",
				Integer.class, userId, success);
	}

	private void insertEvent(UUID userId, String at, boolean success) {
		this.jdbc.update("INSERT INTO login_events (id, user_id, created_at, success, ip) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), userId, Timestamp.from(Instant.parse(at)), success, "127.0.0.1");
	}

}
