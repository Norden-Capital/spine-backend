package com.nordencapital.spine.user;

/**
 * The user management rules (deny by default):
 * <ul>
 * <li>EMPLOYEE: sees and manages nobody, invites nobody.</li>
 * <li>ADMIN: sees EMPLOYEEs and other ADMINs; manages and invites only EMPLOYEEs.</li>
 * <li>OWNER: sees and manages every ADMIN and EMPLOYEE; invites ADMIN or EMPLOYEE.</li>
 * <li>Nobody sees or manages themself here, and nobody can see or manage the OWNER (so
 * the owner cannot be disabled, logged out or changed by others).</li>
 * </ul>
 */
public final class UserAccess {

	private UserAccess() {
	}

	public static boolean canSee(User actor, User target) {
		if (actor.getId().equals(target.getId())) {
			return false;
		}
		return switch (actor.getRole()) {
			case OWNER -> target.getRole() != Role.OWNER;
			case ADMIN -> target.getRole() == Role.ADMIN || target.getRole() == Role.EMPLOYEE;
			case EMPLOYEE -> false;
		};
	}

	public static boolean canManage(User actor, User target) {
		if (!canSee(actor, target)) {
			return false;
		}
		return switch (actor.getRole()) {
			case OWNER -> target.getRole() == Role.ADMIN || target.getRole() == Role.EMPLOYEE;
			case ADMIN -> target.getRole() == Role.EMPLOYEE;
			case EMPLOYEE -> false;
		};
	}

	public static boolean canInvite(User actor, Role role) {
		return switch (actor.getRole()) {
			case OWNER -> role == Role.ADMIN || role == Role.EMPLOYEE;
			case ADMIN -> role == Role.EMPLOYEE;
			case EMPLOYEE -> false;
		};
	}

}
