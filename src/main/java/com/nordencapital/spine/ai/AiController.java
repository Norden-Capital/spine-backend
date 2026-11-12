package com.nordencapital.spine.ai;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "AI", description = "Optional AI help (off when the server has no ANTHROPIC_API_KEY).")
@RestController
public class AiController {

	private final AiService service;

	public AiController(AiService service) {
		this.service = service;
	}

	@Operation(summary = "Which optional features are on")
	@GetMapping("/api/features")
	public Features features() {
		return new Features(this.service.enabled());
	}

	@Operation(summary = "Suggest a one-sentence comment",
			description = "Give transactionId (a company transaction) OR draft. Only type, amount, currency, date, "
					+ "description, category name and tag names are sent to the AI. 20 per user per hour.")
	@ApiResponse(responseCode = "200", description = "A suggestion")
	@ApiResponse(responseCode = "429", description = "too_many_attempts",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "502", description = "ai_unavailable",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "503", description = "ai_disabled",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/ai/suggest-comment")
	public Suggestion suggest(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody SuggestRequest body) {
		CommentSuggester.Input draft = (body.draft() != null) ? body.draft().toInput() : null;
		return new Suggestion(this.service.suggest(actor, body.transactionId(), draft));
	}

	/** @param ai true when AI suggestions are available */
	public record Features(boolean ai) {
	}

	public record Suggestion(String text) {
	}

	public record SuggestRequest(UUID transactionId, @Valid Draft draft) {
	}

	/** A transaction being written. Only description is required. */
	public record Draft(
			@Pattern(regexp = "EXPENSE|INCOME", message = "must be EXPENSE or INCOME") String type,
			@Pattern(regexp = "(0|[1-9][0-9]{0,9})(\\.[0-9]{1,2})?", message = "must be an amount like 123.45")
			String amount,
			@Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String currency,
			@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
			@NotBlank @Size(max = 200) String description, @Size(max = 40) String categoryName,
			@Size(max = 10) List<@Size(max = 40) String> tagNames) {

		CommentSuggester.Input toInput() {
			return new CommentSuggester.Input(this.type, this.amount, this.currency, this.date, this.description,
					this.categoryName, (this.tagNames != null) ? this.tagNames : List.of());
		}

	}

}
