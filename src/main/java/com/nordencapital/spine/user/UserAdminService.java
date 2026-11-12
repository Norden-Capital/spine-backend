package com.nordencapital.spine.user;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthTokenService;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.auth.TokenPurpose;
import com.nordencapital.spine.auth.UserSessions;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Emails;
import com.nordencapital.spine.mail.AuthMails;
import com.nordencapital.spine.me.SignInDay;
import com.nordencapital.spine.me.SignInHistory;
import com.nordencapital.spine.passkey.PasskeyRepository;

/**
 * User management for the OWNER and ADMINs. Who can see and manage whom:
 * {@link UserAccess}.
 * <ul>
 * <li>A user the caller cannot see is "not found" (404), so its existence is not
 * shown.</li>
 * <li>A user the caller can see but not manage gives 403 {@code forbidden}.</li>
 * </ul>
 * The caller is loaded again from the database on every call (not trusted from the
 * session).
 */
@Service
public class UserAdminService {

	private final UserRepository users;

	private final AuthTokenService tokens;

	private final AuthMails mails;

	private final UserSessions sessions;

	private final SignInHistory signInHistory;

	private final Actors actors;

	private final PasskeyRepository passkeys;

	private final Clock clock;

	public UserAdminService(UserRepository users, AuthTokenService tokens, AuthMails mails, UserSessions sessions,
			SignInHistory signInHistory, Actors actors, PasskeyRepository passkeys, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.mails = mails;
		this.sessions = sessions;
		this.signInHistory = signInHistory;
		this.actors = actors;
		this.passkeys = passkeys;
		this.clock = clock;
	}

	/**
	 * Users the caller can see, oldest first.
	 */
	@Transactional(readOnly = true)
	public List<UserResponse> list(AuthUser principal) {
		User actor = actor(principal);
		return this.users.findAllByOrderByCreatedAtAsc()
			.stream()
			.filter((user) -> UserAccess.canSee(actor, user))
			.map((user) -> UserResponse.from(user, UserAccess.canManage(actor, user)))
			.toList();
	}

	/**
	 * @throws ApiException 404 {@code user_not_found}
	 */
	@Transactional(readOnly = true)
	public UserDetailResponse get(AuthUser principal, UUID id) {
		User actor = actor(principal);
		User user = visible(actor, id);
		return UserDetailResponse.from(user, UserAccess.canManage(actor, user),
				this.passkeys.countByUserId(user.getId()));
	}

	/**
	 * Login attempts per day of a user the caller can see, in the caller's time zone.
	 * @throws ApiException 404 {@code user_not_found}
	 */
	@Transactional(readOnly = true)
	public List<SignInDay> signIns(AuthUser principal, UUID id, LocalDate from) {
		User actor = actor(principal);
		User user = visible(actor, id);
		return this.signInHistory.days(user.getId(), actor.getId(), from);
	}

	/**
	 * @throws ApiException 403 {@code forbidden} (not the owner), 409
	 * {@code email_taken}
	 */
	@Transactional
	public UserResponse invite(AuthUser principal, String email) {
		User actor = actor(principal);
		if (!UserAccess.canInvite(actor)) {
			throw forbidden("Only the owner can invite");
		}
		Role role = Role.MEMBER;
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
		return UserResponse.from(user, UserAccess.canManage(actor, user));
	}

	/**
	 * Sends a new invite link (the old one stops working).
	 * @throws ApiException 404, 403, 409 {@code not_invited}
	 */
	@Transactional
	public void resendInvite(AuthUser principal, UUID id) {
		User user = manageable(actor(principal), id);
		if (user.getStatus() != UserStatus.INVITED) {
			throw new ApiException(HttpStatus.CONFLICT, "not_invited", "Only INVITED users can get a new invite");
		}
		this.mails.sendInvite(user.getEmail(), this.tokens.issue(user, TokenPurpose.INVITE));
	}

	/**
	 * Disables the user: logs them out everywhere and their links stop working. Already
	 * disabled: no change.
	 * @throws ApiException 404, 403
	 */
	@Transactional
	public UserResponse disable(AuthUser principal, UUID id) {
		User actor = actor(principal);
		User user = manageable(actor, id);
		if (user.getStatus() != UserStatus.DISABLED) {
			user.setStatus(UserStatus.DISABLED, this.clock.instant());
			this.tokens.deleteAll(user);
			this.sessions.invalidateAll(user.getId());
		}
		return UserResponse.from(user, true);
	}

	/**
	 * Enables a disabled user: ACTIVE again, or INVITED if they never set a password.
	 * Other statuses: no change.
	 * @throws ApiException 404, 403
	 */
	@Transactional
	public UserResponse enable(AuthUser principal, UUID id) {
		User user = manageable(actor(principal), id);
		if (user.getStatus() == UserStatus.DISABLED) {
			UserStatus status = (user.getPasswordHash() != null) ? UserStatus.ACTIVE : UserStatus.INVITED;
			user.setStatus(status, this.clock.instant());
		}
		return UserResponse.from(user, true);
	}

	/**
	 * Logs the user out everywhere.
	 * @throws ApiException 404, 403
	 */
	@Transactional
	public void logoutAll(AuthUser principal, UUID id) {
		User user = manageable(actor(principal), id);
		this.sessions.invalidateAll(user.getId());
	}

	/**
	 * Emails the user a reset-password link (1 hour). Only for ACTIVE users.
	 * @throws ApiException 404, 403, 409 {@code not_active}
	 */
	@Transactional
	public void sendPasswordReset(AuthUser principal, UUID id) {
		User user = manageable(actor(principal), id);
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "not_active", "Only ACTIVE users can get a reset link");
		}
		this.mails.sendPasswordReset(user.getEmail(), this.tokens.issue(user, TokenPurpose.RESET));
	}

	/**
	 * The caller, loaded again. Only the ACTIVE OWNER may use this service.
	 */
	private User actor(AuthUser principal) {
		return this.actors.owner(principal);
	}

	private User visible(User actor, UUID id) {
		return this.users.findById(id)
			.filter((user) -> UserAccess.canSee(actor, user))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found"));
	}

	private User manageable(User actor, UUID id) {
		User user = visible(actor, id);
		if (!UserAccess.canManage(actor, user)) {
			throw forbidden("You cannot manage this user");
		}
		return user;
	}

	private static ApiException forbidden(String message) {
		return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "email_taken", "A user with this email already exists");
	}

}
