package com.nordencapital.spine.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A company transaction (table transactions). Shared by all users. Never hard deleted:
 * {@code deletedAt} set = in the trash.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

	@Id
	private UUID id;

	@Column(nullable = false, length = 10)
	private String type;

	@Column(nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;


	@Column(nullable = false)
	private LocalDate date;

	@Column(nullable = false, length = 200)
	private String description;

	@Column(length = 253)
	private String website;

	@Column(name = "category_id", nullable = false)
	private UUID categoryId;


	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	protected Transaction() {
	}

	static Transaction create(Values values, UUID createdBy, Instant now) {
		Transaction transaction = new Transaction();
		transaction.id = UUID.randomUUID();
		transaction.createdBy = createdBy;
		transaction.createdAt = now;
		transaction.apply(values);
		return transaction;
	}

	void update(Values values, UUID updatedBy, Instant now) {
		apply(values);
		this.updatedBy = updatedBy;
		this.updatedAt = now;
	}

	void trash(UUID by, Instant now) {
		this.deletedAt = now;
		this.deletedBy = by;
	}

	void restore() {
		this.deletedAt = null;
		this.deletedBy = null;
	}

	private void apply(Values values) {
		this.type = values.type();
		this.amount = values.amount();
		this.date = values.date();
		this.description = values.description();
		this.website = values.website();
		this.categoryId = values.categoryId();
	}

	public boolean isTrashed() {
		return this.deletedAt != null;
	}

	public UUID getId() {
		return this.id;
	}

	public String getType() {
		return this.type;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public LocalDate getDate() {
		return this.date;
	}

	public String getDescription() {
		return this.description;
	}

	/** The bare domain like "claude.ai", or null. */
	public String getWebsite() {
		return this.website;
	}

	public UUID getCategoryId() {
		return this.categoryId;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getUpdatedBy() {
		return this.updatedBy;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}

	public UUID getDeletedBy() {
		return this.deletedBy;
	}

	/** Checked values from a create or update request (files are kept on the files table). */
	record Values(String type, BigDecimal amount, LocalDate date, String description,
			String website, UUID categoryId) {
	}

}
