package com.nordencapital.spine.common;

import java.util.Locale;

/**
 * Emails are stored and compared trimmed and lowercased.
 */
public final class Emails {

	private Emails() {
	}

	public static String normalize(String email) {
		return (email != null) ? email.trim().toLowerCase(Locale.ROOT) : null;
	}

}
