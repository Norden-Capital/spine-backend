package com.nordencapital.spine.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthTokenService;
import com.nordencapital.spine.auth.TokenPurpose;
import com.nordencapital.spine.auth.UserSessions;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Emails;
import com.nordencapital.spine.mail.AuthMails;

/**
 * User management for admins: list, invite, resend invite, change role or status.
 */
@Service
public class UserAdminService {

	private final UserRepository users;

	private final AuthTokenService tokens;

	private final AuthMails mails;

	private final UserSessions sessions;

	private final Clock clock;

	public UserAdminService(UserRepository users, AuthTokenService tokens, AuthMails mails, UserSessions sessions,
			Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.mails = mails;
		this.sessions = sessions;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<UserResponse> list() {
		return this.users.findAllByOrderByCreatedAtAsc().stream().map(UserResponse::from).toList();
	}

	/**
	 * @throws ApiException 409 {@code email_taken}
	 */
	@Transactional
	public UserResponse invite(String email, Role role) {
		String normalized = Emails.normalize(email);
		if (this.users.existsByEmail(normalized)) {
			throw emailTaken();
		}
		User user;
		try {
			user = this.users.saveAndFlush(User.invite(normalized, role, this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			throw emailTaken();
		}
		this.mails.sendInvite(user.getEmail(), this.tokens.issue(user, TokenPurpose.INVITE));
		return UserResponse.from(user);
	}

	/**
	 * Sends a new invite link (the old one stops working).
	 * @throws ApiException 404 {@code user_not_found}, 409 {@code not_invited}
	 */
	@Transactional
	public void resendInvite(UUID id) {
		User user = find(id);
		if (user.getStatus() != UserStatus.INVITED) {
			throw new ApiException(HttpStatus.CONFLICT, "not_invited", "Only INVITED users can get a new invite");
		}
		this.mails.sendInvite(user.getEmail(), this.tokens.issue(user, TokenPurpose.INVITE));
	}

	/**
	 * Changes role and/or status.
	 * <ul>
	 * <li>status DISABLED: the user is logged out everywhere and their links stop
	 * working;</li>
	 * <li>status ACTIVE on a DISABLED user: back to ACTIVE (or INVITED if they never set
	 * a password); on an INVITED user it changes nothing;</li>
	 * <li>a role change logs the user out everywhere.</li>
	 * </ul>
	 * @throws ApiException 404 {@code user_not_found}, 400 {@code validation_error}, 409
	 * {@code cannot_disable_self}, 409 {@code last_admin}
	 */
	@Transactional
	public UserResponse update(UUID id, Role role, UserStatus status, UUID actorId) {
		if (status == UserStatus.INVITED) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "status: must be ACTIVE or DISABLED");
		}
		User user = find(id);
		if (status == UserStatus.DISABLED && user.getId().equals(actorId)) {
			throw new ApiException(HttpStatus.CONFLICT, "cannot_disable_self", "You cannot disable yourself");
		}
		Role newRole = (role != null) ? role : user.getRole();
		UserStatus newStatus = newStatus(user, status);

		boolean staysActiveAdmin = newRole == Role.ADMIN && newStatus == UserStatus.ACTIVE;
		if (user.isActiveAdmin() && !staysActiveAdmin) {
			// Locks the active admin rows, so two admins cannot demote each other at once.
			if (this.users.findAllByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE).size() <= 1) {
				throw new ApiException(HttpStatus.CONFLICT, "last_admin",
						"There must always be at least one active admin");
			}
		}

		boolean roleChanged = newRole != user.getRole();
		boolean disabled = newStatus == UserStatus.DISABLED && user.getStatus() != UserStatus.DISABLED;
		Instant now = this.clock.instant();
		user.setRole(newRole, now);
		user.setStatus(newStatus, now);
		if (disabled) {
			this.tokens.deleteAll(user);
		}
		if (roleChanged || disabled) {
			this.sessions.invalidateAll(user.getId());
		}
		return UserResponse.from(user);
	}

	private static UserStatus newStatus(User user, UserStatus requested) {
		if (requested == null) {
			return user.getStatus();
		}
		if (requested == UserStatus.DISABLED) {
			return UserStatus.DISABLED;
		}
		// requested ACTIVE
		if (user.getStatus() == UserStatus.DISABLED) {
			return (user.getPasswordHash() != null) ? UserStatus.ACTIVE : UserStatus.INVITED;
		}
		return user.getStatus();
	}

	private User find(UUID id) {
		return this.users.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found"));
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "email_taken", "A user with this email already exists");
	}

}
