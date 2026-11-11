package com.nordencapital.spine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import com.nordencapital.spine.auth.AuthTokenService;
import com.nordencapital.spine.auth.BootstrapAdmin;
import com.nordencapital.spine.auth.TokenPurpose;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAdminIntegrationTests extends IntegrationTestBase {

	@Autowired
	private BootstrapAdmin bootstrap;

	@Autowired
	private AuthTokenService tokens;

	@Test
	void emptyDatabaseGetsOneInvitedOwnerAndOneEmail() {
		runBootstrap();

		assertThat(this.users.findAll()).singleElement().satisfies((user) -> {
			assertThat(user.getEmail()).isEqualTo("bootstrap@example.com");
			assertThat(user.getRole()).isEqualTo(Role.OWNER);
			assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);
			assertThat(user.getPasswordHash()).isNull();
		});
		assertThat(this.mails.to("bootstrap@example.com")).hasSize(1);
	}

	@Test
	void runningAgainChangesNothing() {
		runBootstrap();
		runBootstrap();
		runBootstrap();

		assertThat(this.users.count()).isEqualTo(1);
		assertThat(this.mails.all()).hasSize(1);
	}

	@Test
	void doesNothingWhenUsersExist() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		runBootstrap();

		assertThat(this.users.findByEmail("bootstrap@example.com")).isEmpty();
		assertThat(this.mails.all()).isEmpty();
	}

	@Test
	void sendsNewInviteWhenFirstOneExpired() {
		runBootstrap();
		expireAllTokens();
		runBootstrap();
		assertThat(this.mails.to("bootstrap@example.com")).hasSize(2);

		// Fresh invite exists now, so no more emails.
		runBootstrap();
		assertThat(this.mails.to("bootstrap@example.com")).hasSize(2);
	}

	@Test
	void doesNothingOnceTheOwnerIsActive() {
		User owner = activeUser("bootstrap@example.com", Role.OWNER);
		runBootstrap();

		assertThat(this.mails.all()).isEmpty();
		assertThat(reload(owner.getId()).getRole()).isEqualTo(Role.OWNER);
	}

	@Test
	void existingBootstrapAdminBecomesTheOwner() {
		// A database from before the OWNER role.
		User old = activeUser("bootstrap@example.com", Role.ADMIN);
		User otherAdmin = activeUser("admin@example.com", Role.ADMIN);
		runBootstrap();

		assertThat(reload(old.getId()).getRole()).isEqualTo(Role.OWNER);
		assertThat(reload(old.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(reload(otherAdmin.getId()).getRole()).isEqualTo(Role.ADMIN);
		assertThat(this.mails.all()).isEmpty();

		runBootstrap();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'OWNER'", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void invitedBootstrapAdminBecomesOwnerAndKeepsItsInviteLink() {
		// Live database state where the first admin never set a password.
		User old = invitedUser("bootstrap@example.com", Role.ADMIN);
		String token = this.transaction.execute((status) -> this.tokens.issue(reload(old.getId()), TokenPurpose.INVITE));
		runBootstrap();

		assertThat(reload(old.getId()).getRole()).isEqualTo(Role.OWNER);
		assertThat(reload(old.getId()).getStatus()).isEqualTo(UserStatus.INVITED);
		assertThat(this.mails.all()).isEmpty();
		assertThat(client().post("/api/auth/set-password",
				"{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}"))
			.hasStatus(org.springframework.http.HttpStatus.NO_CONTENT);
		assertThat(loggedIn("bootstrap@example.com").get("/api/auth/me")).bodyJson()
			.extractingPath("$.role")
			.isEqualTo("OWNER");
	}

	@Test
	void invitedBootstrapAdminWithExpiredInviteGetsANewOneAsOwner() {
		invitedUser("bootstrap@example.com", Role.ADMIN);
		runBootstrap();

		assertThat(this.mails.to("bootstrap@example.com")).hasSize(1);
		assertThat(this.users.findByEmail("bootstrap@example.com").orElseThrow().getRole()).isEqualTo(Role.OWNER);
	}

	@Test
	void disabledBootstrapUserIsEnabledWhenMadeOwner() {
		User old = disabledUser("bootstrap@example.com", Role.ADMIN);
		runBootstrap();

		assertThat(reload(old.getId()).getRole()).isEqualTo(Role.OWNER);
		assertThat(reload(old.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
	}

	@Test
	void anExistingOwnerIsNeverReplaced() {
		User owner = activeUser("first-owner@example.com", Role.OWNER);
		User bootstrapUser = activeUser("bootstrap@example.com", Role.ADMIN);
		runBootstrap();

		assertThat(reload(owner.getId()).getRole()).isEqualTo(Role.OWNER);
		assertThat(reload(bootstrapUser.getId()).getRole()).isEqualTo(Role.ADMIN);
	}

	@Test
	void databaseAllowsOnlyOneOwner() {
		activeUser("owner@example.com", Role.OWNER);
		org.assertj.core.api.Assertions
			.assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
			.isThrownBy(() -> activeUser("owner2@example.com", Role.OWNER));
	}

	@Test
	void ownerCanFinishSetupAndLogIn() {
		runBootstrap();
		String token = tokenFromLastMailTo("bootstrap@example.com");
		client().post("/api/auth/set-password", "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}");

		assertThat(loggedIn("bootstrap@example.com").get("/api/admin/users"))
			.hasStatus(org.springframework.http.HttpStatus.OK);
	}

	private void runBootstrap() {
		this.bootstrap.run(new DefaultApplicationArguments());
	}

}
