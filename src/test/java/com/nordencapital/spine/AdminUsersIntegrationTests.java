package com.nordencapital.spine;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUsersIntegrationTests extends IntegrationTestBase {

	@Test
	void employeeCannotUseAdminApi() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client employee = loggedIn("emp@example.com");
		assertThat(employee.get("/api/admin/users")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(employee.post("/api/admin/users", "{\"email\":\"x@example.com\",\"role\":\"ADMIN\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void adminInvitesUser() throws Exception {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");

		MvcTestResult created = admin.post("/api/admin/users", "{\"email\":\" New@Example.com \",\"role\":\"EMPLOYEE\"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.email").isEqualTo("new@example.com");
		assertThat(created).bodyJson().extractingPath("$.role").isEqualTo("EMPLOYEE");
		assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("INVITED");
		assertThat(created).bodyJson().extractingPath("$.createdAt").isNotNull();
		assertThat(created.getResponse().getContentAsString()).doesNotContain("password");

		String token = tokenFromLastMailTo("new@example.com");
		assertThat(client().get("/api/auth/token/" + token)).hasStatus(HttpStatus.OK);

		MvcTestResult list = admin.get("/api/admin/users");
		assertThat(list).hasStatus(HttpStatus.OK);
		assertThat(list).bodyJson().extractingPath("$[*].email").asArray().contains("admin@example.com",
				"new@example.com");
		assertThat(list.getResponse().getContentAsString()).doesNotContain("password");
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
		User admin = activeUser("admin@example.com", Role.ADMIN);
		Client client = loggedIn("admin@example.com");
		assertThat(client.post("/api/admin/users/" + admin.getId() + "/resend-invite", ""))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("not_invited");
		assertThat(client.post("/api/admin/users/" + java.util.UUID.randomUUID() + "/resend-invite", ""))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void lastActiveAdminCannotBeDemotedOrDisabled() {
		User admin = activeUser("admin@example.com", Role.ADMIN);
		Client client = loggedIn("admin@example.com");

		assertThat(client.patch("/api/admin/users/" + admin.getId(), "{\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("last_admin");
		assertThat(reload(admin.getId()).getRole()).isEqualTo(Role.ADMIN);
	}

	@Test
	void adminCannotDisableThemselves() {
		User admin = activeUser("admin@example.com", Role.ADMIN);
		activeUser("admin2@example.com", Role.ADMIN);
		Client client = loggedIn("admin@example.com");

		assertThat(client.patch("/api/admin/users/" + admin.getId(), "{\"status\":\"DISABLED\"}"))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("cannot_disable_self");
	}

	@Test
	void adminCanDemoteAnotherAdminButNotTheLastOne() {
		User admin1 = activeUser("admin1@example.com", Role.ADMIN);
		User admin2 = activeUser("admin2@example.com", Role.ADMIN);
		Client client = loggedIn("admin1@example.com");

		assertThat(client.patch("/api/admin/users/" + admin2.getId(), "{\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.role")
			.isEqualTo("EMPLOYEE");
		assertThat(client.patch("/api/admin/users/" + admin1.getId(), "{\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void invitedAdminDoesNotCountAsActiveAdmin() {
		User admin = activeUser("admin@example.com", Role.ADMIN);
		invitedUser("pending-admin@example.com", Role.ADMIN);
		Client client = loggedIn("admin@example.com");
		assertThat(client.patch("/api/admin/users/" + admin.getId(), "{\"role\":\"EMPLOYEE\"}"))
			.hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void disablingUserLogsThemOutEverywhere() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client employeeBrowser = loggedIn("emp@example.com");
		Client admin = loggedIn("admin@example.com");

		assertThat(admin.patch("/api/admin/users/" + employee.getId(), "{\"status\":\"DISABLED\"}"))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("DISABLED");

		assertThat(employeeBrowser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(sessionCount(employee.getId())).isZero();
		assertThat(client().login("emp@example.com", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void changingRoleLogsUserOut() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client employeeBrowser = loggedIn("emp@example.com");
		Client admin = loggedIn("admin@example.com");

		admin.patch("/api/admin/users/" + employee.getId(), "{\"role\":\"ADMIN\"}");

		assertThat(employeeBrowser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
		Client again = loggedIn("emp@example.com");
		assertThat(again.get("/api/auth/me")).bodyJson().extractingPath("$.role").isEqualTo("ADMIN");
		assertThat(again.get("/api/admin/users")).hasStatus(HttpStatus.OK);
	}

	@Test
	void reEnablingUser() {
		activeUser("admin@example.com", Role.ADMIN);
		User withPassword = disabledUser("had-password@example.com", Role.EMPLOYEE);
		User neverSetPassword = invitedUser("never@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");
		admin.patch("/api/admin/users/" + neverSetPassword.getId(), "{\"status\":\"DISABLED\"}");

		assertThat(admin.patch("/api/admin/users/" + withPassword.getId(), "{\"status\":\"ACTIVE\"}"))
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("ACTIVE");
		assertThat(admin.patch("/api/admin/users/" + neverSetPassword.getId(), "{\"status\":\"ACTIVE\"}"))
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("INVITED");
		assertThat(reload(withPassword.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
	}

	@Test
	void statusInvitedCannotBeSet() {
		activeUser("admin@example.com", Role.ADMIN);
		User employee = activeUser("emp@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.patch("/api/admin/users/" + employee.getId(), "{\"status\":\"INVITED\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

}
