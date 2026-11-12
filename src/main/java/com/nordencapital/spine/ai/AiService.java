package com.nordencapital.spine.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AttemptLimiter;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.category.Category;
import com.nordencapital.spine.category.CategoryRepository;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.config.AiProperties;
import com.nordencapital.spine.tag.Tag;
import com.nordencapital.spine.tag.TagRepository;
import com.nordencapital.spine.transaction.Transaction;
import com.nordencapital.spine.transaction.TransactionRepository;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * AI comment suggestion for company transactions. Off when no API key is set. Sends
 * only type, amount, currency, date, description, category name and tag names.
 */
@Service
public class AiService {

	private final AiProperties properties;

	private final CommentSuggester suggester;

	private final AttemptLimiter limiter;

	private final Actors actors;

	private final TransactionRepository transactions;

	private final CategoryRepository categories;

	private final TagRepository tags;

	public AiService(AiProperties properties, CommentSuggester suggester, Actors actors,
			TransactionRepository transactions, CategoryRepository categories, TagRepository tags, Clock clock) {
		this.properties = properties;
		this.suggester = suggester;
		this.limiter = new AttemptLimiter(properties.maxPerHour(), Duration.ofHours(1), clock);
		this.actors = actors;
		this.transactions = transactions;
		this.categories = categories;
		this.tags = tags;
	}

	public boolean enabled() {
		return this.properties.enabled();
	}

	/**
	 * @param transactionId a company transaction (or null when a draft is given)
	 * @param draft a transaction being written (or null when an id is given)
	 * @throws ApiException 503 {@code ai_disabled}, 429 {@code too_many_attempts}, 502
	 * {@code ai_unavailable}, 404 {@code transaction_not_found}, 400
	 * {@code validation_error}
	 */
	@Transactional(readOnly = true)
	public String suggest(AuthUser principal, UUID transactionId, CommentSuggester.Input draft) {
		User user = this.actors.active(principal);
		if (!enabled()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_disabled", "AI suggestions are turned off");
		}
		if ((transactionId == null) == (draft == null)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"transactionId: give either transactionId or draft");
		}
		CommentSuggester.Input input = (transactionId != null) ? fromTransaction(transactionId) : draft;
		if (!this.limiter.tryAcquire(user.getId().toString())) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
					"Too many suggestions. Please try again later");
		}
		try {
			return this.suggester.suggest(input).orElseThrow(() -> unavailable(null));
		}
		catch (CommentSuggester.AiUnavailableException ex) {
			throw unavailable(ex);
		}
	}

	private CommentSuggester.Input fromTransaction(UUID id) {
		Transaction tx = this.transactions.findById(id)
			.filter((t) -> !t.isTrashed())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction not found"));
		String categoryName = this.categories.findById(tx.getCategoryId()).map(Category::getName).orElse(null);
		List<String> tagNames = this.tags.findAllById(tx.getTagIds())
			.stream()
			.map(Tag::getName)
			.sorted(Comparator.comparing(String::toLowerCase))
			.toList();
		return new CommentSuggester.Input(tx.getType(), Totals.money(tx.getAmount()), tx.getCurrency(),
				tx.getDate().toString(), tx.getDescription(), categoryName, tagNames);
	}

	private static ApiException unavailable(Throwable cause) {
		return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable",
				"The AI could not make a suggestion right now. Please try again");
	}

	/** Picks the real Claude suggester when a key is set. */
	@Configuration(proxyBeanMethods = false)
	static class AiConfig {

		@Bean
		CommentSuggester commentSuggester(AiProperties properties) {
			if (properties.enabled()) {
				return new AnthropicCommentSuggester(properties);
			}
			return (input) -> {
				throw new CommentSuggester.AiUnavailableException("AI is turned off", null);
			};
		}

	}

}
