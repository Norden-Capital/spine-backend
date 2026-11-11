package com.nordencapital.spine;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User management rules: OWNER &gt; ADMIN &gt; EMPLOYEE.
 */
class AdminUsersIntegrationTests extends IntegrationTestBase {

	private static final String[] ACTIONS = { "disable", "enable", "logout-all", "reset-password", "resend-invite" };

	@Test
	void employeeCannotUseAdminApi() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		User other = activeUser("emp2@example.com", Role.EMPLOYEE);
		Client employee = loggedIn("emp@example.com");

		assertForbidden(employee.get("/api/admin/users"));
		assertForbidden(employee.get("/api/admin/users/" + other.getId()));
		assertForbidden(employee.post("/api/admin/users", "{\"email\":\"x@example.com\",\"role\":\"EMPLOYEE\"}"));
		for (String action : ACTIONS) {
			assertForbidden(employee.post("/api/admin/users/" + other.getId() + "/" + action, ""));
		}
	}

	@Test
	void adminSeesEmployeesAndOtherAdminsButNotSelfOrOwner() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		User admin = activeUser("admin@example.com", Role.ADMIN);
		User admin2 = activeUser("admin2@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("admin@example.com");

		MvcTestResult list = client.get("/api/admin/users");
		assertThat(list).hasStatus(HttpStatus.OK);
		assertThat(list).bodyJson()
			.extractingPath("$[*].email")
			.asArray()
			.containsExactly("admin2@example.com", "emp@example.com");
		assertThat(list).bodyJson().extractingPath("$[0].canManage").isEqualTo(false);
		assertThat(list).bodyJson().extractingPath("$[1].canManage").isEqualTo(true);

		assertThat(client.get("/api/admin/users/" + admin2.getId())).hasStatus(HttpStatus.OK);
		assertThat(client.get("/api/admin/users/" + employee.getId())).hasStatus(HttpStatus.OK);
		assertNotFound(client.get("/api/admin/users/" + owner.getId()));
		assertNotFound(client.get("/api/admin/users/" + admin.getId()));
		assertNotFound(client.get("/api/admin/users/" + owner.getId() + "/sign-ins"));
	}

	@Test
	void adminCannotActOnAdminsSelfOrOwner() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		User admin = activeUser("admin@example.com", Role.ADMIN);
		User admin2 = activeUser("admin2@example.com", Role.ADMIN);
		Client client = loggedIn("admin@example.com");
		Client ownerSession = loggedIn("owner@example.com");

		for (String action : ACTIONS) {
			assertForbidden(client.post("/api/admin/users/" + admin2.getId() + "/" + action, ""));
			assertNotFound(client.post("/api/admin/users/" + owner.getId() + "/" + action, ""));
			assertNotFound(client.post("/api/admin/users/" + admin.getId() + "/" + action, ""));
		}
		assertThat(reload(admin2.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(reload(owner.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(ownerSession.get("/api/me")).hasStatus(HttpStatus.OK);
		assertThat(this.mails.all()).isEmpty();
	}

	@Test
	void adminCanInviteOnlyEmployees() throws Exception {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");

		MvcTestResult created = admin.post("/api/admin/users", "{\"email\":\" New@Example.com \",\"role\":\"EMPLOYEE\"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.email").isEqualTo("new@example.com");
		assertThat(created).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");
		assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("INVITED");
		assertThat(created).bodyJson().extractingPath("$.canManage").isEqualTo(true);
		assertThat(created).bodyJson().extractingPath("$.firstName").isNull();
		assertThat(created.getResponse().getContentAsString()).doesNotContain("password");
		String token = tokenFromLastMailTo("new@example.com");
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK);

		assertForbidden(admin.post("/api/admin/users", "{\"email\":\"a2@example.com\",\"role\":\"ADMIN\"}"));
		assertForbidden(admin.post("/api/admin/users", "{\"email\":\"o2@example.com\",\"role\":\"OWNER\"}"));
		assertThat(this.users.findByEmail("a2@example.com")).isEmpty();
	}

	@Test
	void ownerSeesEveryoneButSelfAndCanManageThem() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		User admin = activeUser("admin@example.com", Role.ADMIN);
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("owner@example.com");

		MvcTestResult list = client.get("/api/admin/users");
		assertThat(list).bodyJson()
			.extractingPath("$[*].email")
			.asArray()
			.containsExactly("admin@example.com", "emp@example.com");
		assertThat(list).bodyJson().extractingPath("$[*].canManage").asArray().containsOnly(true);
		assertNotFound(client.get("/api/admin/users/" + owner.getId()));

		Client adminSession = loggedIn("admin@example.com");
		assertThat(client.post("/api/admin/users/" + admin.getId() + "/disable", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("DISABLED");
		assertThat(adminSession.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessionCount(admin.getId())).isZero();

		assertThat(client.post("/api/admin/users/" + admin.getId() + "/enable", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("ACTIVE");
	}

	@Test
	void ownerCanInviteAdminButNotOwner() {
		activeUser("owner@example.com", Role.OWNER);
		Client client = loggedIn("owner@example.com");

		assertThat(client.post("/api/admin/users", "{\"email\":\"a2@example.com\",\"role\":\"ADMIN\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertThat(client.post("/api/admin/users", "{\"email\":\"e2@example.com\",\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertForbidden(client.post("/api/admin/users", "{\"email\":\"o2@example.com\",\"role\":\"OWNER\"}"));
	}

	@Test
	void ownerCannotActOnThemself() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		Client client = loggedIn("owner@example.com");
		for (String action : ACTIONS) {
			assertNotFound(client.post("/api/admin/users/" + owner.getId() + "/" + action, ""));
		}
		assertThat(reload(owner.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(client.get("/api/me")).bodyJson().extractingPath("$.role").isEqualTo("OWNER");
		assertThat(client.get("/api/auth/me")).bodyJson().extractingPath("$.role").isEqualTo("OWNER");
	}

	@Test
	void inviteRejectsTakenEmailAnyCase() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.post("/api/admin/users", "{\"email\":\"ADMIN@example.com\",\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("email_taken");
	}

	@Test
	void inviteValidatesInput() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.post("/api/admin/users", "{\"email\":\"not-an-email\",\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(admin.post("/api/admin/users", "{\"email\":\"a@example.com\",\"role\":\"BOSS\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void resendInviteSendsNewLinkAndKillsOldOne() {
		activeUser("admin@example.com", Role.ADMIN);
		User invited = invitedUser("new@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");

		admin.post("/api/admin/users/" + invited.getId() + "/resend-invite", "");
		String first = tokenFromLastMailTo("new@example.com");
		assertThat(admin.post("/api/admin/users/" + invited.getId() + "/resend-invite", ""))
			.hasStatus(HttpStatus.NO_CONTENT);
		String second = tokenFromLastMailTo("new@example.com");

		assertThat(client().get("/api/auth/token/" + first)).hasStatus(HttpStatus.GONE);
		assertThat(client().get("/api/auth/token/" + second)).hasStatus(HttpStatus.OK);
	}

	@Test
	void resendInviteOnlyForInvitedUsers() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("admin@example.com");
		assertThat(client.post("/api/admin/users/" + employee.getId() + "/resend-invite", ""))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("not_invited");
		assertNotFound(client.post("/api/admin/users/" + UUID.randomUUID() + "/resend-invite", ""));
	}

	@Test
	void disableLogsOutEverywhereAndKillsLinks() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");
		Client employeeSession = loggedIn("emp@example.com");
		client().post("/api/auth/forgot-password", "{\"email\":\"emp@example.com\"}");
		String resetToken = tokenFromLastMailTo("emp@example.com");

		assertThat(admin.post("/api/admin/users/" + employee.getId() + "/disable", "")).hasStatus(HttpStatus.OK);

		assertThat(employeeSession.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().get("/api/auth/token/" + resetToken)).hasStatus(HttpStatus.GONE);
		assertThat(client().login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void enableGivesBackActiveOrInvited() {
		activeUser("admin@example.com", Role.ADMIN);
		User withPassword = disabledUser("had-password@example.com", Role.EMPLOYEE);
		User neverSetPassword = this.transaction.execute((status) -> {
			User user = User.invite("never@example.com", Role.EMPLOYEE, Instant.now());
			user.setStatus(UserStatus.DISABLED, Instant.now());
			return this.users.save(user);
		});
		Client admin = loggedIn("admin@example.com");

		assertThat(admin.post("/api/admin/users/" + withPassword.getId() + "/enable", "")).bodyJson()
			.extractingPath("$.status")
			.isEqualTo("ACTIVE");
		assertThat(admin.post("/api/admin/users/" + neverSetPassword.getId() + "/enable", "")).bodyJson()
			.extractingPath("$.status")
			.isEqualTo("INVITED");
		assertThat(client().login("had-password@example.com", PASSWORD)).hasStatus(HttpStatus.OK);
	}

	@Test
	void logoutAllDeletesEverySessionOfThatUser() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");
		Client phone = loggedIn("emp@example.com");
		Client laptop = loggedIn("emp@example.com");

		assertThat(admin.post("/api/admin/users/" + employee.getId() + "/logout-all", ""))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(phone.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(laptop.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(admin.get("/api/me")).hasStatus(HttpStatus.OK);
		assertThat(reload(employee.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
	}

	@Test
	void resetPasswordEmailsThatUserOnlyIfActive() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		User invited = invitedUser("new@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");

		assertThat(admin.post("/api/admin/users/" + employee.getId() + "/reset-password", ""))
			.hasStatus(HttpStatus.ACCEPTED);
		assertThat(this.mails.all()).singleElement().satisfies((mail) -> {
			assertThat(mail.to()).isEqualTo("emp@example.com");
			assertThat(mail.subject()).isEqualTo("Reset your Spine password");
		});
		String token = tokenFromLastMailTo("emp@example.com");
		assertThat(client().post("/api/auth/set-password",
				"{\"token\":\"" + token + "\",\"password\":\"another-good-password\"}"))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(client().login("emp@example.com", "another-good-password")).hasStatus(HttpStatus.OK);

		assertThat(admin.post("/api/admin/users/" + invited.getId() + "/reset-password", ""))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("not_active");
	}

	@Test
	void signInsOfAVisibleUser() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		this.jdbc.update("INSERT INTO login_events (id, user_id, created_at, success, ip) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), employee.getId(), Timestamp.from(Instant.parse("2026-03-10T12:00:00Z")), false,
				"127.0.0.1");
		Client admin = loggedIn("admin@example.com");

		assertThat(admin.get("/api/admin/users/" + employee.getId() + "/sign-ins?from=2026-03-01"))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.isStrictlyEqualTo("[{\"date\":\"2026-03-10\",\"ok\":0,\"failed\":1}]");
	}

	@Test
	void disabledAdminLosesAccessAtOnce() {
		activeUser("owner@example.com", Role.OWNER);
		User admin = activeUser("admin@example.com", Role.ADMIN);
		Client adminSession = loggedIn("admin@example.com");
		// Disabled directly in the database: the old session must not work anymore.
		this.jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", admin.getId());

		assertForbidden(adminSession.get("/api/admin/users"));
	}

	private static void assertForbidden(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("forbidden");
	}

	private static void assertNotFound(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("user_not_found");
	}

}
