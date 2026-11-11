package com.nordencapital.spine.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

import com.nordencapital.spine.user.Role;

/**
 * The logged-in user, stored in the server-side session.
 * <p>
 * {@link #getName()} returns the user id, so Spring Session indexes sessions by user id
 * and we can delete all sessions of a user.
 */
public record AuthUser(UUID id, String email, Role role) implements AuthenticatedPrincipal, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	public String getName() {
		return this.id.toString();
	}

}
