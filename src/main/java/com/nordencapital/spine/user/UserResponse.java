package com.nordencapital.spine.user;

import java.time.Instant;
import java.util.UUID;

/**
 * A user as the admin API returns it. Never contains the password hash.
 * @param canManage true if the caller may act on this user (disable, enable, log out,
 * reset password, resend invite)
 */
public record UserResponse(UUID id, String email, String firstName, String lastName, Role role, UserStatus status,
		Instant createdAt, Instant lastLoginAt, boolean canManage) {

	public static UserResponse from(User user, boolean canManage) {
		return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
				user.getRole(), user.getStatus(), user.getCreatedAt(), user.getLastLoginAt(), canManage);
	}

}
