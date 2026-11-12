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
import com.nordencapital.spine.common.ListFilter;
import com.nordencapital.spine.common.ListPage;

@Tag(name = "Transactions", description = "Company transactions, shared: every logged-in user sees and edits all. "
		+ "Change and delete (to the trash): its creator or the OWNER; restore is OWNER. Every write needs the "
		+ "X-XSRF-TOKEN header.")
@RestController
public class TransactionController {

	private final TransactionService service;

	public TransactionController(TransactionService service) {
		this.service = service;
	}

	@Operation(summary = "Search transactions (filters, paging, totals)",
			description = "Not trashed. Newest first. Totals count ALL matches, not only this page. Without "
					+ "month/from/to: this month in my time zone preference. See docs/api-module5.md (A).")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "validation_error (bad filter)",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/transactions")
	public ListPage<TransactionResponse> list(@AuthenticationPrincipal AuthUser actor,
			@Parameter(description = "First date YYYY-MM-DD (included)") @RequestParam(required = false) String from,
			@Parameter(description = "Last date YYYY-MM-DD (included)") @RequestParam(required = false) String to,
			@Parameter(description = "YYYY-MM, used when from/to are not given") @RequestParam(
					required = false) String month,
			@Parameter(description = "Text in the description or a comment") @RequestParam(required = false) String q,
			@Parameter(description = "Category ids (a parent includes its children)") @RequestParam(
					required = false) List<String> categoryIds,
			@RequestParam(required = false) String type,
			@Parameter(description = "User id (OWNER only, else 403)") @RequestParam(required = false) String createdBy,
			@RequestParam(required = false) String minAmount, @RequestParam(required = false) String maxAmount,
			@Parameter(description = "0-based, default 0") @RequestParam(required = false) String page,
			@Parameter(description = "Default 50, max 100") @RequestParam(required = false) String size) {
		return this.service.list(actor, new ListFilter.Params(from, to, month, q, categoryIds, type,
				createdBy, minAmount, maxAmount, page, size));
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
	@ApiResponse(responseCode = "409", description = "possible_duplicate (send again with confirmDuplicate: true)")
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

	@Operation(summary = "Move a transaction to the trash", description = "Its creator or the OWNER.")
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

	@Operation(summary = "The trash (OWNER)", description = "Last trashed first.")
	@GetMapping("/api/admin/transactions/trash")
	public List<TrashedTransactionResponse> trashList(@AuthenticationPrincipal AuthUser actor) {
		return this.service.trashList(actor);
	}

	@Operation(summary = "Restore from the trash (OWNER)")
	@ApiResponse(responseCode = "200", description = "Restored")
	@ApiResponse(responseCode = "404", description = "transaction_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/transactions/{id}/restore")
	public TransactionResponse restore(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.restore(actor, id);
	}

}
