package com.nordencapital.spine.transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.nordencapital.spine.file.FileResponse;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.transaction.TransactionResponse.TagRef;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;

/**
 * A transaction in the trash: the normal fields plus when and by whom it was trashed.
 */
public record TrashedTransactionResponse(UUID id, String type, String amount, String currency, LocalDate date,
		String description, CategoryRef category, List<TagRef> tags, List<FileResponse> files, UserRef createdBy,
		Instant createdAt, UserRef updatedBy, Instant updatedAt, boolean canDelete, Instant deletedAt,
		UserRef deletedBy) {

	static TrashedTransactionResponse of(TransactionResponse tx, Instant deletedAt, UserRef deletedBy) {
		return new TrashedTransactionResponse(tx.id(), tx.type(), tx.amount(), tx.currency(), tx.date(),
				tx.description(), tx.category(), tx.tags(), tx.files(), tx.createdBy(), tx.createdAt(), tx.updatedBy(),
				tx.updatedAt(), tx.canDelete(), deletedAt, deletedBy);
	}

}
