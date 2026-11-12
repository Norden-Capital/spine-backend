package com.nordencapital.spine.common;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new transaction looks like one that already exists (same type, amount, currency
 * and date). Answered as 409 {@code possible_duplicate} with the matches; the client
 * can send again with "confirmDuplicate": true.
 */
public class DuplicateException extends RuntimeException {

	private final List<Match> matches;

	public DuplicateException(List<Match> matches) {
		super("A transaction with the same type, amount, currency and date already exists");
		this.matches = List.copyOf(matches);
	}

	public List<Match> getMatches() {
		return this.matches;
	}

	/** A transaction that looks the same. */
	public record Match(UUID id, String description, UserRef createdBy, Instant createdAt) {
	}

	/** A user shown by name. */
	public record UserRef(UUID id, String name) {
	}

	/** The 409 body. */
	public record Body(String error, String message, List<Match> matches) {
	}

}
