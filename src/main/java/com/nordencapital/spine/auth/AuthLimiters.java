package com.nordencapital.spine.auth;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.nordencapital.spine.config.AppProperties;

/**
 * In-memory limits, keyed by email + client IP:
 * <ul>
 * <li>login: after {@code login-max-failures} failed tries, blocked until
 * {@code login-lock-time} after the first failure (429);</li>
 * <li>forgot password: at most {@code forgot-password-max-requests} per
 * {@code forgot-password-window}; extra requests still get 202 but send nothing.</li>
 * </ul>
 */
@Component
public class AuthLimiters {

	private final AttemptLimiter login;

	private final AttemptLimiter forgotPassword;

	public AuthLimiters(AppProperties properties, Clock clock) {
		AppProperties.Auth auth = properties.auth();
		this.login = new AttemptLimiter(auth.loginMaxFailures(), auth.loginLockTime(), clock);
		this.forgotPassword = new AttemptLimiter(auth.forgotPasswordMaxRequests(), auth.forgotPasswordWindow(), clock);
	}

	public AttemptLimiter login() {
		return this.login;
	}

	public AttemptLimiter forgotPassword() {
		return this.forgotPassword;
	}

	/** Forgets all counters (used by tests). */
	public void clearAll() {
		this.login.clear();
		this.forgotPassword.clear();
	}

}
