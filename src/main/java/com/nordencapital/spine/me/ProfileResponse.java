package com.nordencapital.spine.me;

import java.time.Instant;
import java.util.UUID;

import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

/**
 * The logged-in user's own profile. Names are null if not set.
 */
public record ProfileResponse(UUID id, String email, Role role, UserStatus status, String firstName,
		String lastName, Instant createdAt, Instant lastLoginAt, Instant previousLoginAt, String avatarSeed,
		boolean avatarIsDefault) {

	/**
	 * @param previousLoginAt the successful login before this session's one, or null
	 */
	static ProfileResponse from(User user, Instant previousLoginAt) {
		return new ProfileResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(),
				user.getFirstName(), user.getLastName(), user.getCreatedAt(), user.getLastLoginAt(), previousLoginAt,
				user.avatarSeed(), user.avatarSeed().equals(user.getId().toString()));
	}

}
