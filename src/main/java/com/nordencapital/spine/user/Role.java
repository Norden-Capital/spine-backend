package com.nordencapital.spine.user;

/**
 * OWNER &gt; ADMIN &gt; EMPLOYEE. There is exactly one OWNER (the bootstrap user). Who can
 * see and manage whom: {@link UserAccess}.
 */
public enum Role {

	OWNER, ADMIN, EMPLOYEE

}
