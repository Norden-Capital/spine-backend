package com.nordencapital.spine.user;

import java.util.UUID;

/**
 * Seeds of the drawn faces (DiceBear "Notionists"). A user without a chosen seed gets a
 * stable default made from the user id only (never the email or name, so nothing
 * personal goes into the picture seed).
 */
public final class Avatars {

	/** Allowed chosen seeds. */
	public static final String SEED_PATTERN = "[A-Za-z0-9-]{1,64}";

	public static final int MAX_SEED = 64;

	private Avatars() {
	}

	/** The chosen seed, or the default for this user id. */
	public static String seed(UUID userId, String chosen) {
		return (chosen != null) ? chosen : userId.toString();
	}

}
