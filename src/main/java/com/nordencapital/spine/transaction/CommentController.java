package com.nordencapital.spine.transaction;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;
import com.nordencapital.spine.transaction.CommentService.CommentResponse;

@Tag(name = "Comments", description = "Comments on company transactions. Not part of the history.")
@RestController
public class CommentController {

	private final CommentService service;

	public CommentController(CommentService service) {
		this.service = service;
	}

	@Operation(summary = "Comments of a transaction", description = "Oldest first.")
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/transactions/{id}/comments")
	public List<CommentResponse> list(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.list(actor, id);
	}

	@Operation(summary = "Add a comment", description = "Text: 1 to 500 characters (trimmed).")
	@ApiResponse(responseCode = "201", description = "Added")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/transactions/{id}/comments")
	public ResponseEntity<CommentResponse> add(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody CommentRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.add(actor, id, body.text()));
	}

	@Operation(summary = "Delete a comment", description = "The author or the OWNER.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "transaction_not_found or comment_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@DeleteMapping("/api/transactions/{id}/comments/{commentId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@PathVariable UUID commentId) {
		this.service.delete(actor, id, commentId);
		return ResponseEntity.noContent().build();
	}

	/** Trimmed before validation. */
	public record CommentRequest(@NotBlank @Size(max = 500) String text) {

		public CommentRequest {
			text = (text != null) ? text.strip() : null;
		}

	}

}
