package com.nordencapital.spine.user;

/**
 * OWNER (exactly one: the bootstrap user) and MEMBER. The owner manages users,
 * categories, the trash and the company repeat expenses, and can change any company
 * transaction; a member changes only the company transactions they added. Who can see
 * and manage whom: {@link UserAccess}.
 */
public enum Role {

	OWNER, MEMBER

}
