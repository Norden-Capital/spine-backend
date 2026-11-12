package com.nordencapital.spine.transaction;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Transactions", description = "Company transactions, shared: every logged-in user sees and edits all. "
		+ "Delete moves to the trash (its creator, OWNER, ADMIN); restore is OWNER/ADMIN. Every write needs the "
		+ "X-XSRF-TOKEN header.")
@RestController
public class TransactionController {

	private final TransactionService service;

	public TransactionController(TransactionService service) {
		this.service = service;
	}

	@Operation(summary = "One month of transactions, with totals per currency",
			description = "Newest first. Trashed ones are not shown.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "validation_error (bad month)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/transactions")
	public TransactionList month(@AuthenticationPrincipal AuthUser actor,
			@Parameter(description = "YYYY-MM. Default: this month in my time zone preference (UTC if automatic).")
			@RequestParam(required = false) String month) {
		return this.service.month(actor, month);
	}

	@Operation(summary = "One transaction")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/transactions/{id}")
	public TransactionResponse get(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.get(actor, id);
	}

	@Operation(summary = "Add a transaction")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "validation_error, proof_required or future_date",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/transactions")
	public ResponseEntity<TransactionResponse> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody TransactionRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.create(actor, body));
	}

	@Operation(summary = "Change a transaction (send all fields)",
			description = "Files left out are removed from it. Only real changes go to the history.")
	@ApiResponse(responseCode = "200", description = "Saved")
	@ApiResponse(responseCode = "400", description = "validation_error, proof_required or future_date",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PutMapping("/api/transactions/{id}")
	public TransactionResponse update(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody TransactionRequest body) {
		return this.service.update(actor, id, body);
	}

	@Operation(summary = "Move a transaction to the trash", description = "Its creator, OWNER or ADMIN.")
	@ApiResponse(responseCode = "204", description = "In the trash")
	@ApiResponse(responseCode = "403", description = "forbidden",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@DeleteMapping("/api/transactions/{id}")
	public ResponseEntity<Void> trash(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.trash(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "History of a transaction", description = "Newest first.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/transactions/{id}/history")
	public List<HistoryEntry> history(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.history(actor, id);
	}

	@Operation(summary = "The trash (OWNER and ADMIN)", description = "Last trashed first.")
	@GetMapping("/api/admin/transactions/trash")
	public List<TrashedTransactionResponse> trashList(@AuthenticationPrincipal AuthUser actor) {
		return this.service.trashList(actor);
	}

	@Operation(summary = "Restore from the trash (OWNER and ADMIN)")
	@ApiResponse(responseCode = "200", description = "Restored")
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/transactions/{id}/restore")
	public TransactionResponse restore(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.restore(actor, id);
	}

}
