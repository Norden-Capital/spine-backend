package com.nordencapital.spine.transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nordencapital.spine.file.FileResponse;

/**
 * A transaction as the API returns it. The amount is a string like "123.45".
 * @param website bare domain like "claude.ai", or null
 * @param logoUrl path of the website's logo (GET it with the session), or null: show the
 * category icon
 * @param updatedBy null if never changed
 * @param updatedAt null if never changed
 * @param canEdit true if the caller may change it and its proof files (the person who
 * added it, or the OWNER)
 * @param canDelete true if the caller may move it to the trash (same rule)
 */
public record TransactionResponse(UUID id, String type, String amount, LocalDate date,
		String description, String website, String logoUrl, CategoryRef category, List<FileResponse> files, UserRef createdBy,
		Instant createdAt, UserRef updatedBy, Instant updatedAt, boolean canEdit, boolean canDelete) {

	/** @param kind EXPENSE or INCOME */
	public record CategoryRef(UUID id, String name, String icon, boolean archived, UUID parentId, String kind) {
	}

	/**
	 * A user shown by name ("First Last", or the email) and face.
	 * @param avatarSeed DiceBear seed (never null)
	 */
	public record UserRef(UUID id, String name, String avatarSeed) {
	}

}
