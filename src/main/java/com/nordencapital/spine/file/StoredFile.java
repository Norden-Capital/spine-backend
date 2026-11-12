package com.nordencapital.spine.file;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An uploaded proof file (table files). The bytes are on disk under a random name
 * ({@code storageKey}); the original name is only shown to users.
 */
@Entity
@Table(name = "files")
public class StoredFile {

	@Id
	private UUID id;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(name = "original_name", nullable = false, length = 255)
	private String originalName;

	@Column(name = "content_type", nullable = false, length = 100)
	private String contentType;

	@Column(nullable = false)
	private long size;

	@Column(name = "storage_key", nullable = false, unique = true, length = 64)
	private String storageKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Null until the file is attached to a transaction. */
	@Column(name = "transaction_id")
	private UUID transactionId;

	protected StoredFile() {
	}

	static StoredFile create(UUID ownerId, String originalName, String contentType, long size, String storageKey,
			Instant now) {
		StoredFile file = new StoredFile();
		file.id = UUID.randomUUID();
		file.ownerId = ownerId;
		file.originalName = originalName;
		file.contentType = contentType;
		file.size = size;
		file.storageKey = storageKey;
		file.createdAt = now;
		return file;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getOwnerId() {
		return this.ownerId;
	}

	public String getOriginalName() {
		return this.originalName;
	}

	public String getContentType() {
		return this.contentType;
	}

	public long getSize() {
		return this.size;
	}

	public String getStorageKey() {
		return this.storageKey;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getTransactionId() {
		return this.transactionId;
	}

	public void attachTo(UUID transactionId) {
		this.transactionId = transactionId;
	}

	public void detach() {
		this.transactionId = null;
	}

}
