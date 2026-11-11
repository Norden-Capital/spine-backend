package com.nordencapital.spine.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.nordencapital.spine.user.User;

/**
 * A one-time invite or reset token. Only the SHA-256 hash of the token is stored.
 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TokenPurpose purpose;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuthToken() {
	}

	AuthToken(User user, TokenPurpose purpose, String tokenHash, Instant expiresAt, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.user = user;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return this.id;
	}

	public User getUser() {
		return this.user;
	}

	public TokenPurpose getPurpose() {
		return this.purpose;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

}
