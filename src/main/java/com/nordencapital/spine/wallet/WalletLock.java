package com.nordencapital.spine.wallet;

import java.io.Serial;
import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthLimiters;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.auth.PasswordPolicy;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * The wallet PIN and the unlock flag.
 * <ul>
 * <li>PIN: 6 digits, Argon2 hashed, set once (reset with the account password).</li>
 * <li>Unlock: kept in the server session for {@value #IDLE_MINUTES} minutes; every
 * wallet request starts the 15 minutes again. Logout locks it (the session is gone).</li>
 * <li>{@value #MAX_FAILURES} wrong PINs: locked for {@value #LOCK_MINUTES} minutes
 * (stored in the database, so a restart does not reset it).</li>
 * </ul>
 */
@Component
public class WalletLock {

	static final int IDLE_MINUTES = 15;

	static final int MAX_FAILURES = 5;

	static final int LOCK_MINUTES = 5;

	private static final String SESSION_KEY = "spine.wallet.unlock";

	private final JdbcTemplate jdbc;

	private final PasswordEncoder passwordEncoder;

	private final Actors actors;

	private final AuthLimiters limiters;

	private final WalletCategories categories;

	private final Clock clock;

	public WalletLock(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Actors actors, AuthLimiters limiters,
			WalletCategories categories, Clock clock) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.actors = actors;
		this.limiters = limiters;
		this.categories = categories;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Status status(AuthUser principal, HttpSession session) {
		User user = this.actors.active(principal);
		Optional<Settings> settings = settings(user.getId());
		Instant lockedUntil = settings.map(Settings::lockedUntil)
			.filter((until) -> until.isAfter(this.clock.instant()))
			.orElse(null);
		return new Status(settings.isPresent(), settings.isPresent() && isUnlocked(user.getId(), session), lockedUntil);
	}

	/**
	 * First PIN. Also makes my starting categories and unlocks the wallet.
	 * @throws ApiException 409 {@code pin_exists}, 400 {@code validation_error}
	 */
	@Transactional
	public void setPin(AuthUser principal, HttpSession session, String pin) {
		User user = this.actors.active(principal);
		checkPinFormat("pin", pin);
		if (settings(user.getId()).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "pin_exists", "The wallet already has a PIN");
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO wallet_settings (user_id, pin_hash, failed_attempts, created_at, updated_at) "
				+ "VALUES (?, ?, 0, ?, ?) ON CONFLICT (user_id) DO NOTHING", user.getId(),
				this.passwordEncoder.encode(pin), now, now);
		this.categories.seed(user.getId());
		unlockSession(user.getId(), session);
	}

	/**
	 * @throws ApiException 400 {@code invalid_pin}, 429 {@code too_many_attempts}, 409
	 * {@code no_pin}
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void unlock(AuthUser principal, HttpSession session, String pin) {
		User user = this.actors.active(principal);
		Settings settings = settings(user.getId()).orElseThrow(WalletLock::noPin);
		Instant now = this.clock.instant();
		if (settings.lockedUntil() != null && settings.lockedUntil().isAfter(now)) {
			throw tooMany();
		}
		boolean ok = pin != null && pin.length() <= 20 && this.passwordEncoder.matches(pin, settings.pinHash());
		if (!ok) {
			int failures = settings.failedAttempts() + 1;
			if (failures >= MAX_FAILURES) {
				this.jdbc.update("UPDATE wallet_settings SET failed_attempts = 0, locked_until = ? WHERE user_id = ?",
						Timestamp.from(now.plus(Duration.ofMinutes(LOCK_MINUTES))), user.getId());
				throw tooMany();
			}
			this.jdbc.update("UPDATE wallet_settings SET failed_attempts = ? WHERE user_id = ?", failures, user.getId());
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_pin", "Wrong PIN");
		}
		this.jdbc.update("UPDATE wallet_settings SET failed_attempts = 0, locked_until = NULL WHERE user_id = ?",
				user.getId());
		unlockSession(user.getId(), session);
	}

	public void lock(HttpSession session) {
		if (session != null) {
			session.removeAttribute(SESSION_KEY);
		}
	}

	/**
	 * New PIN, checked with the account password. Also clears the wrong-PIN lock and
	 * unlocks.
	 * @throws ApiException 400 {@code invalid_password}, 429 {@code too_many_attempts},
	 * 409 {@code no_pin}, 400 {@code validation_error}
	 */
	@Transactional
	public void resetPin(AuthUser principal, HttpSession session, String password, String newPin) {
		User user = this.actors.active(principal);
		checkPinFormat("newPin", newPin);
		settings(user.getId()).orElseThrow(WalletLock::noPin);
		String limitKey = "wallet|" + user.getId();
		if (this.limiters.changePassword().isBlocked(limitKey)) {
			throw tooMany();
		}
		boolean ok = password != null && !PasswordPolicy.isTooLong(password) && user.getPasswordHash() != null
				&& this.passwordEncoder.matches(password, user.getPasswordHash());
		if (!ok) {
			this.limiters.changePassword().record(limitKey);
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_password", "The account password is wrong");
		}
		this.limiters.changePassword().reset(limitKey);
		this.jdbc.update("UPDATE wallet_settings SET pin_hash = ?, failed_attempts = 0, locked_until = NULL, "
				+ "updated_at = ? WHERE user_id = ?", this.passwordEncoder.encode(newPin),
				Timestamp.from(this.clock.instant()), user.getId());
		unlockSession(user.getId(), session);
	}

	/**
	 * For every wallet request except status, PIN and unlock: the wallet must be
	 * unlocked in this session. Starts the 15 idle minutes again.
	 * @return the caller
	 * @throws ApiException 409 {@code no_pin}, 423 {@code wallet_locked}
	 */
	@Transactional(readOnly = true)
	public User requireUnlocked(AuthUser principal, HttpSession session) {
		User user = this.actors.active(principal);
		if (settings(user.getId()).isEmpty()) {
			throw noPin();
		}
		if (!isUnlocked(user.getId(), session)) {
			throw locked();
		}
		unlockSession(user.getId(), session);
		return user;
	}

	/** True if the wallet of this user is unlocked in this session (does not extend it). */
	public boolean isUnlocked(UUID userId, HttpSession session) {
		if (session == null) {
			return false;
		}
		return session.getAttribute(SESSION_KEY) instanceof Unlock unlock && unlock.userId().equals(userId)
				&& unlock.expiresAt().isAfter(this.clock.instant());
	}

	static ApiException locked() {
		return new ApiException(HttpStatus.LOCKED, "wallet_locked", "Unlock the wallet with your PIN first");
	}

	private void unlockSession(UUID userId, HttpSession session) {
		session.setAttribute(SESSION_KEY,
				new Unlock(userId, this.clock.instant().plus(Duration.ofMinutes(IDLE_MINUTES))));
	}

	private Optional<Settings> settings(UUID userId) {
		List<Settings> rows = this.jdbc.query(
				"SELECT pin_hash, failed_attempts, locked_until FROM wallet_settings WHERE user_id = ?",
				(rs, row) -> new Settings(rs.getString("pin_hash"), rs.getInt("failed_attempts"),
						(rs.getTimestamp("locked_until") != null) ? rs.getTimestamp("locked_until").toInstant() : null),
				userId);
		return rows.stream().findFirst();
	}

	private static void checkPinFormat(String field, String pin) {
		if (pin == null || !pin.matches("[0-9]{6}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", field + ": must be exactly 6 digits");
		}
	}

	private static ApiException noPin() {
		return new ApiException(HttpStatus.CONFLICT, "no_pin", "Set a wallet PIN first");
	}

	private static ApiException tooMany() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
				"Too many attempts. Please wait and try again later");
	}

	private record Settings(String pinHash, int failedAttempts, Instant lockedUntil) {
	}

	/** @param lockedUntil set while locked after too many wrong PINs */
	public record Status(boolean hasPin, boolean unlocked, Instant lockedUntil) {
	}

	/** Kept in the session while the wallet is unlocked. */
	record Unlock(UUID userId, Instant expiresAt) implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

	}

}
