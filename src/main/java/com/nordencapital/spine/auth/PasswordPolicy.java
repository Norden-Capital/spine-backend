package com.nordencapital.spine.auth;

import org.springframework.http.HttpStatus;

import com.nordencapital.spine.common.ApiException;

/**
 * Password rules: 12 to 128 characters.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 12;

	public static final int MAX_LENGTH = 128;

	private PasswordPolicy() {
	}

	public static boolean isTooLong(String password) {
		return password != null && password.length() > MAX_LENGTH;
	}

	/**
	 * @throws ApiException 400 {@code weak_password} if the password breaks the rules
	 */
	public static void check(String password) {
		if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "weak_password",
					"Password must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long");
		}
	}

}
