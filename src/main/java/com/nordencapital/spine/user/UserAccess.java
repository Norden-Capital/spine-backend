package com.nordencapital.spine.user;

/**
 * The user management rules (deny by default):
 * <ul>
 * <li>MEMBER: sees and manages nobody, invites nobody.</li>
 * <li>OWNER: sees and manages every MEMBER; invites members.</li>
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
		return actor.getRole() == Role.OWNER && target.getRole() == Role.MEMBER;
	}

	public static boolean canManage(User actor, User target) {
		return canSee(actor, target);
	}

	/** Only the owner invites; a new user is always a MEMBER. */
	public static boolean canInvite(User actor) {
		return actor.getRole() == Role.OWNER;
	}

}
