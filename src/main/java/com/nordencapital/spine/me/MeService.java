package com.nordencapital.spine.me;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthLimiters;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.auth.PasswordPolicy;
import com.nordencapital.spine.auth.UserSessions;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;
import com.nordencapital.spine.user.UserStatus;

/**
 * The logged-in user's own data: profile, names, login history, password, preferences.
 * Every method works only on the caller's own user (from the session), never on an id
 * from the request.
 */
@Service
public class MeService {

	private final UserRepository users;

	private final UserPreferencesRepository preferences;

	private final SignInHistory signInHistory;

	private final PasswordEncoder passwordEncoder;

	private final UserSessions sessions;

	private final AuthLimiters limiters;

	private final Clock clock;

	public MeService(UserRepository users, UserPreferencesRepository preferences, SignInHistory signInHistory,
			PasswordEncoder passwordEncoder, UserSessions sessions, AuthLimiters limiters, Clock clock) {
		this.users = users;
		this.preferences = preferences;
		this.signInHistory = signInHistory;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.limiters = limiters;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public User profile(AuthUser principal) {
		return activeUser(principal);
	}

	/**
	 * Sets both names (already trimmed; null = not set).
	 */
	@Transactional
	public User updateNames(AuthUser principal, String firstName, String lastName) {
		User user = activeUser(principal);
		user.setNames(firstName, lastName, this.clock.instant());
		return user;
	}

	/**
	 * My login attempts per day, in my time zone preference (UTC if not set).
	 * @param from first day; null = today minus 6 months
	 */
	@Transactional(readOnly = true)
	public List<SignInDay> signIns(AuthUser principal, LocalDate from) {
		User user = activeUser(principal);
		return this.signInHistory.days(user.getId(), user.getId(), from);
	}

	/**
	 * Changes the password after checking the current one. On success, the user stays
	 * logged in on this session and is logged out everywhere else.
	 * @throws ApiException 400 {@code invalid_current_password}, 400
	 * {@code weak_password} or 429 {@code too_many_attempts} (after too many wrong
	 * current passwords)
	 */
	@Transactional
	public void changePassword(AuthUser principal, String currentPassword, String newPassword,
			String keepSessionId) {
		String limitKey = principal.id().toString();
		if (this.limiters.changePassword().isBlocked(limitKey)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
					"Too many attempts. Please wait and try again later");
		}
		User user = activeUser(principal);
		boolean currentOk = currentPassword != null && !PasswordPolicy.isTooLong(currentPassword)
				&& user.getPasswordHash() != null
				&& this.passwordEncoder.matches(currentPassword, user.getPasswordHash());
		if (!currentOk) {
			this.limiters.changePassword().record(limitKey);
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_current_password",
					"The current password is wrong");
		}
		PasswordPolicy.check(newPassword);
		user.setPasswordHash(this.passwordEncoder.encode(newPassword), this.clock.instant());
		this.limiters.changePassword().reset(limitKey);
		this.sessions.invalidateAllExcept(user.getId(), keepSessionId);
	}

	@Transactional(readOnly = true)
	public Preferences preferences(AuthUser principal) {
		return preferencesOf(activeUser(principal));
	}

	/**
	 * Saves all preferences (fields already checked by bean validation).
	 * @throws ApiException 400 {@code validation_error} if the time zone is unknown
	 */
	@Transactional
	public Preferences savePreferences(AuthUser principal, Preferences values) {
		if (values.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(values.timeZone())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"timeZone: must be an IANA time zone id like America/Toronto, or null");
		}
		User user = activeUser(principal);
		UserPreferences row = this.preferences.findById(user.getId())
			.orElseGet(() -> new UserPreferences(user.getId()));
		row.update(values, this.clock.instant());
		return this.preferences.save(row).toPreferences();
	}

	private Preferences preferencesOf(User user) {
		return this.preferences.findById(user.getId())
			.map(UserPreferences::toPreferences)
			.orElse(Preferences.DEFAULTS);
	}

	/**
	 * Loads the caller again, so a stale session of a disabled user gets nothing.
	 */
	private User activeUser(AuthUser principal) {
		return this.users.findById(principal.id())
			.filter((user) -> user.getStatus() == UserStatus.ACTIVE)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Please log in"));
	}

}
