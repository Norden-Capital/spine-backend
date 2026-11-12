package com.nordencapital.spine.category;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

	@Id
	private UUID id;

	@Column(nullable = false, length = 40)
	private String name;

	@Column(nullable = false, length = 40)
	private String icon;

	@Column(nullable = false)
	private boolean archived;

	/** 0-based order among its siblings (same parent). */
	@Column(nullable = false)
	private int position;

	/** EXPENSE or INCOME (a subcategory has the kind of its main category). */
	@Column(nullable = false, length = 10)
	private String kind;

	/** Bare domain like "claude.ai" (its logo is shown), or null. */
	@Column(length = 253)
	private String website;

	/** Null = top level. One level only. */
	@Column(name = "parent_id")
	private UUID parentId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Category() {
	}

	static Category create(String name, String icon, UUID parentId, int position, String kind, Instant now) {
		Category category = new Category();
		category.parentId = parentId;
		category.id = UUID.randomUUID();
		category.name = name;
		category.icon = icon;
		category.position = position;
		category.kind = kind;
		category.createdAt = now;
		category.updatedAt = now;
		return category;
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getIcon() {
		return this.icon;
	}

	public boolean isArchived() {
		return this.archived;
	}

	public int getPosition() {
		return this.position;
	}

	/** Order only (no updatedAt change: renumbering siblings is not an edit of them). */
	void setPosition(int position) {
		this.position = position;
	}

	public UUID getParentId() {
		return this.parentId;
	}

	public String getKind() {
		return this.kind;
	}

	void changeKind(String kind, Instant now) {
		this.kind = kind;
		this.updatedAt = now;
	}

	public String getWebsite() {
		return this.website;
	}

	void changeWebsite(String website, Instant now) {
		this.website = website;
		this.updatedAt = now;
	}

	void moveTo(UUID parentId, Instant now) {
		this.parentId = parentId;
		this.updatedAt = now;
	}

	void rename(String name, Instant now) {
		this.name = name;
		this.updatedAt = now;
	}

	void changeIcon(String icon, Instant now) {
		this.icon = icon;
		this.updatedAt = now;
	}

	void setArchived(boolean archived, Instant now) {
		this.archived = archived;
		this.updatedAt = now;
	}

}
