package com.nordencapital.spine.common;

import java.util.Locale;

/**
 * Safe SQL LIKE patterns: %, _ and \ in user text are matched as plain characters. Use
 * with {@code LIKE ? ESCAPE '\'} on {@code lower(column)}.
 */
public final class Like {

	private Like() {
	}

	/** "%text%", lower case, with %, _ and \ escaped. */
	public static String contains(String text) {
		String escaped = text.toLowerCase(Locale.ROOT)
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
