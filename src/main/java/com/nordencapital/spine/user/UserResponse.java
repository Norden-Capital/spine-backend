package com.nordencapital.spine.user;

import java.time.Instant;
import java.util.UUID;

/**
 * A user as the admin API returns it. Never contains the password hash.
 */
public record UserResponse(UUID id, String email, Role role, UserStatus status, Instant createdAt,
		Instant lastLoginAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(), user.getCreatedAt(),
				user.getLastLoginAt());
	}

}
