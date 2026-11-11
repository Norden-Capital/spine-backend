package com.nordencapital.spine.auth;

public enum TokenPurpose {

	/** Sent when an admin invites a user. The user sets a first password. */
	INVITE,

	/** Sent by "forgot password". The user sets a new password. */
	RESET

}
