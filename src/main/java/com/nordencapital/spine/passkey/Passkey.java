package com.nordencapital.spine.passkey;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One passkey of one user (table passkeys).
 */
@Entity
@Table(name = "passkeys")
public class Passkey {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "credential_id", nullable = false, unique = true)
	private byte[] credentialId;

	/** COSE-encoded public key. */
	@Column(name = "public_key", nullable = false)
	private byte[] publicKey;

	@Column(name = "sign_count", nullable = false)
	private long signCount;

	@Column(length = 200)
	private String transports;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	protected Passkey() {
	}

	static Passkey create(UUID userId, byte[] credentialId, byte[] publicKey, long signCount, String transports,
			String name, Instant now) {
		Passkey passkey = new Passkey();
		passkey.id = UUID.randomUUID();
		passkey.userId = userId;
		passkey.credentialId = credentialId;
		passkey.publicKey = publicKey;
		passkey.signCount = signCount;
		passkey.transports = transports;
		passkey.name = name;
		passkey.createdAt = now;
		return passkey;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getUserId() {
		return this.userId;
	}

	public byte[] getCredentialId() {
		return this.credentialId;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	public long getSignCount() {
		return this.signCount;
	}

	public String getTransports() {
		return this.transports;
	}

	public String getName() {
		return this.name;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getLastUsedAt() {
		return this.lastUsedAt;
	}

	void rename(String name) {
		this.name = name;
	}

	void recordUse(long signCount, Instant now) {
		this.signCount = signCount;
		this.lastUsedAt = now;
	}

}
