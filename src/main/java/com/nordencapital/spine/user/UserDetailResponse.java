package com.nordencapital.spine.user;

import java.time.Instant;
import java.util.UUID;

/**
 * One user for the admin user page: the list fields plus how many passkeys they have.
 */
public record UserDetailResponse(UUID id, String email, String firstName, String lastName, Role role,
		UserStatus status, Instant createdAt, Instant lastLoginAt, boolean canManage, long passkeyCount,
		String avatarSeed) {

	static UserDetailResponse from(User user, boolean canManage, long passkeyCount) {
		return new UserDetailResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
				user.getRole(), user.getStatus(), user.getCreatedAt(), user.getLastLoginAt(), canManage, passkeyCount,
				user.avatarSeed());
	}

}
