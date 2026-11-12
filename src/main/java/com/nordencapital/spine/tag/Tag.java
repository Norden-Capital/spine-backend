package com.nordencapital.spine.tag;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tags")
public class Tag {

	@Id
	private UUID id;

	@Column(nullable = false, length = 40)
	private String name;

	@Column(nullable = false)
	private boolean archived;

	/** Who created it; null if that user was deleted. */
	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Tag() {
	}

	static Tag create(String name, UUID createdBy, Instant now) {
		Tag tag = new Tag();
		tag.id = UUID.randomUUID();
		tag.name = name;
		tag.createdBy = createdBy;
		tag.createdAt = now;
		tag.updatedAt = now;
		return tag;
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public boolean isArchived() {
		return this.archived;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	void rename(String name, Instant now) {
		this.name = name;
		this.updatedAt = now;
	}

	void setArchived(boolean archived, Instant now) {
		this.archived = archived;
		this.updatedAt = now;
	}

}
