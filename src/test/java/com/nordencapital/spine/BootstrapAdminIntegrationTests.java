package com.nordencapital.spine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import com.nordencapital.spine.auth.BootstrapAdmin;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAdminIntegrationTests extends IntegrationTestBase {

	@Autowired
	private BootstrapAdmin bootstrap;

	@Test
	void emptyDatabaseGetsOneInvitedAdminAndOneEmail() {
		runBootstrap();

		assertThat(this.users.findAll()).singleElement().satisfies((user) -> {
			assertThat(user.getEmail()).isEqualTo("bootstrap@example.com");
			assertThat(user.getRole()).isEqualTo(Role.ADMIN);
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
	void doesNothingOnceAnAdminIsActive() {
		runBootstrap();
		expireAllTokens();
		User other = activeUser("admin@example.com", Role.ADMIN);
		runBootstrap();

		assertThat(this.mails.all()).hasSize(1);
		assertThat(other.getStatus()).isEqualTo(UserStatus.ACTIVE);
	}

	@Test
	void bootstrapAdminCanFinishSetupAndLogIn() {
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
