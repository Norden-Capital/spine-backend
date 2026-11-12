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
 * @param canDelete true if the caller may move it to the trash (its creator, OWNER,
 * ADMIN)
 */
public record TransactionResponse(UUID id, String type, String amount, String currency, LocalDate date,
		String description, String website, String logoUrl, CategoryRef category, List<TagRef> tags, List<FileResponse> files, UserRef createdBy,
		Instant createdAt, UserRef updatedBy, Instant updatedAt, boolean canDelete) {

	public record CategoryRef(UUID id, String name, String icon, boolean archived, UUID parentId) {
	}

	public record TagRef(UUID id, String name) {
	}

	/** A user shown by name ("First Last", or the email). */
	public record UserRef(UUID id, String name) {
	}

}
