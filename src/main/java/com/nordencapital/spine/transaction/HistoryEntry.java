package com.nordencapital.spine.transaction;

import java.time.Instant;
import java.util.List;

import com.nordencapital.spine.transaction.TransactionResponse.UserRef;

/**
 * One change of a transaction.
 * @param action CREATED, UPDATED, DELETED or RESTORED
 * @param changes the fields that changed (empty for DELETED and RESTORED)
 */
public record HistoryEntry(Instant at, UserRef by, String action, List<Change> changes) {

	/**
	 * One field, as display text. category, tags and files are shown by name (tags and
	 * files as "a, b"). {@code from} is null on CREATED.
	 */
	public record Change(String field, String from, String to) {
	}

}
