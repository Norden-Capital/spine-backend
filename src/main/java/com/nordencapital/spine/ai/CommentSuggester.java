package com.nordencapital.spine.ai;

import java.util.List;
import java.util.Optional;

/**
 * Writes one short comment suggestion for a transaction.
 */
public interface CommentSuggester {

	/**
	 * @return the sentence, or empty if the AI gave none (for example a refusal)
	 * @throws AiUnavailableException the AI service failed or timed out
	 */
	Optional<String> suggest(Input input);

	/**
	 * The ONLY data sent to the AI: no files, no names, no emails.
	 * @param amount like "123.45" (may be null)
	 */
	record Input(String type, String amount, String currency, String date, String description, String categoryName,
			List<String> tagNames) {
	}

	/** The AI service failed or timed out. */
	class AiUnavailableException extends RuntimeException {

		public AiUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
