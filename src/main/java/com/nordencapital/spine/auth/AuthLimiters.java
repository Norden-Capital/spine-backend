package com.nordencapital.spine.auth;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.nordencapital.spine.config.AppProperties;

/**
 * In-memory limits. Login and forgot password are keyed by email + client IP; change
 * password is keyed by user id:
 * <ul>
 * <li>login: after {@code login-max-failures} failed tries, blocked until
 * {@code login-lock-time} after the first failure (429);</li>
 * <li>forgot password: at most {@code forgot-password-max-requests} per
 * {@code forgot-password-window}; extra requests still get 202 but send nothing;</li>
 * <li>change password: same rules as login, for wrong current passwords (429);</li>
 * <li>passkey login: same rules as login, keyed by client IP (no email is typed).</li>
 * </ul>
 */
@Component
public class AuthLimiters {

	private final AttemptLimiter login;

	private final AttemptLimiter forgotPassword;

	private final AttemptLimiter changePassword;

	private final AttemptLimiter passkeyLogin;

	public AuthLimiters(AppProperties properties, Clock clock) {
		AppProperties.Auth auth = properties.auth();
		this.login = new AttemptLimiter(auth.loginMaxFailures(), auth.loginLockTime(), clock);
		this.forgotPassword = new AttemptLimiter(auth.forgotPasswordMaxRequests(), auth.forgotPasswordWindow(), clock);
		this.changePassword = new AttemptLimiter(auth.loginMaxFailures(), auth.loginLockTime(), clock);
		this.passkeyLogin = new AttemptLimiter(auth.loginMaxFailures(), auth.loginLockTime(), clock);
	}

	public AttemptLimiter login() {
		return this.login;
	}

	public AttemptLimiter forgotPassword() {
		return this.forgotPassword;
	}

	public AttemptLimiter changePassword() {
		return this.changePassword;
	}

	public AttemptLimiter passkeyLogin() {
		return this.passkeyLogin;
	}

	/** Forgets all counters (used by tests). */
	public void clearAll() {
		this.login.clear();
		this.forgotPassword.clear();
		this.changePassword.clear();
		this.passkeyLogin.clear();
	}

}
