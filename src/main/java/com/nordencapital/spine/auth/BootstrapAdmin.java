package com.nordencapital.spine.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.nordencapital.spine.common.Emails;
import com.nordencapital.spine.config.AppProperties;
import com.nordencapital.spine.mail.AuthMails;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;
import com.nordencapital.spine.user.UserStatus;

/**
 * Creates the OWNER on startup.
 * <ul>
 * <li>No user at all: invite {@code APP_BOOTSTRAP_ADMIN_EMAIL} as OWNER (status INVITED)
 * and email a set-password link.</li>
 * <li>No OWNER yet but the bootstrap email's user exists (databases from before the
 * OWNER role): make that user the OWNER. A disabled one is enabled again.</li>
 * <li>Recovery: the OWNER is still INVITED and its invite has expired: send a new invite
 * (so a restart fixes a lost or expired first link).</li>
 * <li>Anything else: do nothing. Safe to run on every start.</li>
 * </ul>
 * No password is ever stored in code or config.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(BootstrapAdmin.class);

	private final UserRepository users;

	private final AuthTokenService tokens;

	private final AuthMails mails;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final String adminEmail;

	public BootstrapAdmin(UserRepository users, AuthTokenService tokens, AuthMails mails,
			TransactionTemplate transaction, Clock clock, AppProperties properties) {
		this.users = users;
		this.tokens = tokens;
		this.mails = mails;
		this.transaction = transaction;
		this.clock = clock;
		this.adminEmail = Emails.normalize(properties.bootstrap().adminEmail());
	}

	@Override
	public void run(ApplicationArguments args) {
		if (this.adminEmail == null || this.adminEmail.isBlank()) {
			logger.info("Bootstrap admin: APP_BOOTSTRAP_ADMIN_EMAIL is empty, skipping");
			return;
		}
		try {
			this.transaction.executeWithoutResult((status) -> bootstrap());
		}
		catch (DataIntegrityViolationException ex) {
			// Another instance created the admin at the same time.
			logger.info("Bootstrap admin: already created by another instance");
		}
	}

	private void bootstrap() {
		if (this.users.count() == 0) {
			User owner = this.users.save(User.invite(this.adminEmail, Role.OWNER, this.clock.instant()));
			this.users.flush();
			this.mails.sendInvite(owner.getEmail(), this.tokens.issue(owner, TokenPurpose.INVITE));
			logger.info("Bootstrap admin: invited {} as the OWNER", owner.getEmail());
			return;
		}
		Optional<User> owner = this.users.findFirstByRole(Role.OWNER).or(this::promoteBootstrapUser);
		owner.filter((user) -> user.getStatus() == UserStatus.INVITED)
			.filter((user) -> !this.tokens.hasValidToken(user, TokenPurpose.INVITE))
			.ifPresent((user) -> {
				this.mails.sendInvite(user.getEmail(), this.tokens.issue(user, TokenPurpose.INVITE));
				logger.info("Bootstrap admin: invite for {} had expired, sent a new one", user.getEmail());
			});
	}

	/**
	 * Makes the bootstrap email's user the OWNER (for databases from before the OWNER
	 * role).
	 */
	private Optional<User> promoteBootstrapUser() {
		Optional<User> found = this.users.findByEmail(this.adminEmail);
		found.ifPresent((user) -> {
			Instant now = this.clock.instant();
			user.setRole(Role.OWNER, now);
			if (user.getStatus() == UserStatus.DISABLED) {
				user.setStatus((user.getPasswordHash() != null) ? UserStatus.ACTIVE : UserStatus.INVITED, now);
			}
			this.users.flush();
			logger.info("Bootstrap admin: {} is now the OWNER", user.getEmail());
		});
		return found;
	}

}
