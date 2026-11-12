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

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Category() {
	}

	static Category create(String name, String icon, int sortOrder, Instant now) {
		Category category = new Category();
		category.id = UUID.randomUUID();
		category.name = name;
		category.icon = icon;
		category.sortOrder = sortOrder;
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

	public int getSortOrder() {
		return this.sortOrder;
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
