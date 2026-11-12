package com.nordencapital.spine.passkey;

import java.time.Instant;
import java.util.UUID;

/**
 * A passkey as "My account" shows it. No key material.
 */
public record PasskeyResponse(UUID id, String name, Instant createdAt, Instant lastUsedAt) {

	static PasskeyResponse from(Passkey passkey) {
		return new PasskeyResponse(passkey.getId(), passkey.getName(), passkey.getCreatedAt(),
				passkey.getLastUsedAt());
	}

}
