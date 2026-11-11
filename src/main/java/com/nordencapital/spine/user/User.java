package com.nordencapital.spine.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	/** Argon2 hash with its {id} prefix. Null until the user sets a password. */
	@Column(name = "password_hash")
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	protected User() {
	}

	public static User invite(String email, Role role, Instant now) {
		User user = new User();
		user.id = UUID.randomUUID();
		user.email = email;
		user.role = role;
		user.status = UserStatus.INVITED;
		user.createdAt = now;
		user.updatedAt = now;
		return user;
	}

	public boolean isActiveAdmin() {
		return this.role == Role.ADMIN && this.status == UserStatus.ACTIVE;
	}

	public UUID getId() {
		return this.id;
	}

	public String getEmail() {
		return this.email;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public Role getRole() {
		return this.role;
	}

	public UserStatus getStatus() {
		return this.status;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getLastLoginAt() {
		return this.lastLoginAt;
	}

	public void setPasswordHash(String passwordHash, Instant now) {
		this.passwordHash = passwordHash;
		this.updatedAt = now;
	}

	public void setRole(Role role, Instant now) {
		this.role = role;
		this.updatedAt = now;
	}

	public void setStatus(UserStatus status, Instant now) {
		this.status = status;
		this.updatedAt = now;
	}

	public void recordLogin(Instant now) {
		this.lastLoginAt = now;
	}

}
