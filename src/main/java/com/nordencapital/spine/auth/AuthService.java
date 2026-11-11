package com.nordencapital.spine.auth;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Emails;
import com.nordencapital.spine.mail.AuthMails;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;
import com.nordencapital.spine.user.UserStatus;

/**
 * Login, forgot password and set password.
 */
@Service
public class AuthService {

	private final UserRepository users;

	private final AuthTokenService tokens;

	private final PasswordEncoder passwordEncoder;

	private final AuthMails mails;

	private final UserSessions sessions;

	private final AuthLimiters limiters;

	private final Clock clock;

	/**
	 * Checked when the email is unknown or cannot log in, so every failed login takes
	 * about the same time (no user enumeration by timing).
	 */
	private final String dummyHash;

	public AuthService(UserRepository users, AuthTokenService tokens, PasswordEncoder passwordEncoder,
			AuthMails mails, UserSessions sessions, AuthLimiters limiters, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.mails = mails;
		this.sessions = sessions;
		this.limiters = limiters;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * Checks email and password. Unknown, invited and disabled users all get the same
	 * {@code invalid_credentials} error.
	 * @throws ApiException 401 {@code invalid_credentials} or 429
	 * {@code too_many_attempts}
	 */
	@Transactional
	public AuthUser login(String email, String password, String clientIp) {
		String normalized = Emails.normalize(email);
		String limitKey = normalized + "|" + clientIp;
		if (this.limiters.login().isBlocked(limitKey)) {
			throw tooManyAttempts();
		}
		Optional<User> found = this.users.findByEmail(normalized);
		boolean canLogin = found.map((user) -> user.getStatus() == UserStatus.ACTIVE && user.getPasswordHash() != null)
			.orElse(false);
		String hash = canLogin ? found.get().getPasswordHash() : this.dummyHash;
		boolean passwordOk = !PasswordPolicy.isTooLong(password) && this.passwordEncoder.matches(password, hash);
		if (!canLogin || !passwordOk) {
			this.limiters.login().record(limitKey);
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password");
		}
		this.limiters.login().reset(limitKey);
		User user = found.get();
		user.recordLogin(this.clock.instant());
		return new AuthUser(user.getId(), user.getEmail(), user.getRole());
	}

	/**
	 * Sends a reset link if the email belongs to an ACTIVE user. Always returns quietly,
	 * so the caller cannot tell whether the email exists. Over the limit, nothing is
	 * sent.
	 */
	@Transactional
	public void forgotPassword(String email, String clientIp) {
		String normalized = Emails.normalize(email);
		if (!this.limiters.forgotPassword().tryAcquire(normalized + "|" + clientIp)) {
			return;
		}
		this.users.findByEmail(normalized).filter((user) -> user.getStatus() == UserStatus.ACTIVE).ifPresent((user) -> {
			String rawToken = this.tokens.issue(user, TokenPurpose.RESET);
			this.mails.sendPasswordReset(user.getEmail(), rawToken);
		});
	}

	/**
	 * @throws ApiException 410 {@code token_invalid}
	 */
	@Transactional(readOnly = true)
	public TokenInfo checkToken(String rawToken) {
		AuthToken token = this.tokens.findValid(rawToken).orElseThrow(AuthService::tokenInvalid);
		return new TokenInfo(token.getUser().getEmail(), token.getPurpose());
	}

	/**
	 * Sets the password, marks the user ACTIVE and uses up the token. Does not log in.
	 * After a reset, all sessions of the user are logged out.
	 * @throws ApiException 410 {@code token_invalid} or 400 {@code weak_password}
	 */
	@Transactional
	public void setPassword(String rawToken, String password) {
		AuthToken token = this.tokens.findValid(rawToken).orElseThrow(AuthService::tokenInvalid);
		PasswordPolicy.check(password);
		User user = token.getUser();
		user.setPasswordHash(this.passwordEncoder.encode(password), this.clock.instant());
		user.setStatus(UserStatus.ACTIVE, this.clock.instant());
		this.tokens.deleteAll(user);
		this.sessions.invalidateAll(user.getId());
	}

	/**
	 * Loads the logged-in user again, so a stale session cannot outlive a disabled user.
	 * @return empty if the user is gone or not ACTIVE any more
	 */
	@Transactional(readOnly = true)
	public Optional<AuthUser> current(AuthUser principal) {
		return this.users.findById(principal.id())
			.filter((user) -> user.getStatus() == UserStatus.ACTIVE)
			.map((user) -> new AuthUser(user.getId(), user.getEmail(), user.getRole()));
	}

	private static ApiException tokenInvalid() {
		return new ApiException(HttpStatus.GONE, "token_invalid", "This link is invalid or has expired");
	}

	private static ApiException tooManyAttempts() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
				"Too many attempts. Please wait and try again later");
	}

	public record TokenInfo(String email, TokenPurpose purpose) {
	}

}
