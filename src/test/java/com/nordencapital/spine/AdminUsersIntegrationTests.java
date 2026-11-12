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
 * User management rules: one OWNER, everyone else MEMBER.
 */
class AdminUsersIntegrationTests extends IntegrationTestBase {

	private static final String[] ACTIONS = { "disable", "enable", "logout-all", "reset-password", "resend-invite" };

	@Test
	void memberCannotUseAdminApi() {
		activeUser("emp@example.com", Role.MEMBER);
		User other = activeUser("emp2@example.com", Role.MEMBER);
		Client member = loggedIn("emp@example.com");

		assertForbidden(member.get("/api/admin/users"));
		assertForbidden(member.get("/api/admin/users/" + other.getId()));
		assertForbidden(member.post("/api/admin/users", "{\"email\":\"x@example.com\"}"));
		for (String action : ACTIONS) {
			assertForbidden(member.post("/api/admin/users/" + other.getId() + "/" + action, ""));
		}
		assertThat(this.users.findByEmail("x@example.com")).isEmpty();
	}

	@Test
	void ownerSeesEveryMemberButNotSelfAndManagesThem() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		User member = activeUser("member@example.com", Role.MEMBER);
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("owner@example.com");

		MvcTestResult list = client.get("/api/admin/users");
		assertThat(list).bodyJson()
			.extractingPath("$[*].email")
			.asArray()
			.containsExactlyInAnyOrder("emp@example.com", "member@example.com");
		assertThat(list).bodyJson().extractingPath("$[*].canManage").asArray().containsOnly(true);
		assertThat(list).bodyJson().extractingPath("$[*].role").asArray().containsOnly("MEMBER");
		assertNotFound(client.get("/api/admin/users/" + owner.getId()));
		assertNotFound(client.get("/api/admin/users/" + owner.getId() + "/sign-ins"));

		Client memberSession = loggedIn("member@example.com");
		assertThat(client.post("/api/admin/users/" + member.getId() + "/disable", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("DISABLED");
		assertThat(memberSession.get("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessionCount(member.getId())).isZero();

		assertThat(client.post("/api/admin/users/" + member.getId() + "/enable", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("ACTIVE");
	}

	@Test
	void ownerInvitesAlwaysAMember() throws Exception {
		activeUser("owner@example.com", Role.OWNER);
		Client owner = loggedIn("owner@example.com");

		MvcTestResult created = owner.post("/api/admin/users", "{\"email\":\" New@Example.com \"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.email").isEqualTo("new@example.com");
		assertThat(created).bodyJson().extractingPath("$.role").isEqualTo("MEMBER");
		assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("INVITED");
		assertThat(created).bodyJson().extractingPath("$.canManage").isEqualTo(true);
		assertThat(created.getResponse().getContentAsString()).doesNotContain("password");
		String token = tokenFromLastMailTo("new@example.com");
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK);

		// A role sent by an old client is ignored: still a MEMBER, never a second owner.
		assertThat(owner.post("/api/admin/users", "{\"email\":\"o2@example.com\",\"role\":\"OWNER\"}"))
			.bodyJson()
			.extractingPath("$.role")
			.isEqualTo("MEMBER");
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
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.post("/api/admin/users", "{\"email\":\"ADMIN@example.com\"}"))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("email_taken");
	}

	@Test
	void inviteValidatesInput() {
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.post("/api/admin/users", "{\"email\":\"not-an-email\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(admin.post("/api/admin/users", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void resendInviteSendsNewLinkAndKillsOldOne() {
		activeUser("admin@example.com", Role.OWNER);
		User invited = invitedUser("new@example.com", Role.MEMBER);
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
		activeUser("admin@example.com", Role.OWNER);
		User employee = activeUser("emp@example.com", Role.MEMBER);
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
		activeUser("admin@example.com", Role.OWNER);
		User employee = activeUser("emp@example.com", Role.MEMBER);
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
		activeUser("admin@example.com", Role.OWNER);
		User withPassword = disabledUser("had-password@example.com", Role.MEMBER);
		User neverSetPassword = this.transaction.execute((status) -> {
			User user = User.invite("never@example.com", Role.MEMBER, Instant.now());
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
		activeUser("admin@example.com", Role.OWNER);
		User employee = activeUser("emp@example.com", Role.MEMBER);
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
		activeUser("admin@example.com", Role.OWNER);
		User employee = activeUser("emp@example.com", Role.MEMBER);
		User invited = invitedUser("new@example.com", Role.MEMBER);
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
		activeUser("admin@example.com", Role.OWNER);
		User employee = activeUser("emp@example.com", Role.MEMBER);
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
	void disabledOwnerLosesAccessAtOnce() {
		User admin = activeUser("admin@example.com", Role.OWNER);
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
