package com.nordencapital.spine.user;

public enum UserStatus {

	/** Invited by email, has not set a password yet. */
	INVITED,

	/** Has a password and can log in. */
	ACTIVE,

	/** Blocked by an admin. Cannot log in. */
	DISABLED

}
