package com.nordencapital.spine.balance;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiError;

@Tag(name = "Balances", description = "Company balances: Spine guesses each balance from its last start point. "
		+ "Everyone in the company reads, adds, edits, recharges, fixes and deletes any balance. Delete moves it to "
		+ "the trash; only the OWNER sees the trash and restores. The /api/admin/balances paths are OWNER aliases.")
@RestController
public class BalanceController {

	private final BalanceService service;

	public BalanceController(BalanceService service) {
		this.service = service;
	}

	@Operation(summary = "All balances", description = "Most urgent first, with the summary.")
	@GetMapping("/api/balances")
	public BalanceApi.BalanceList list(@AuthenticationPrincipal AuthUser actor) {
		return this.service.list(actor);
	}

	@Operation(summary = "One balance with its history", description = "In the trash: OWNER only (else 404).")
	@ApiResponse(responseCode = "404", description = "balance_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@GetMapping("/api/balances/{id}")
	public BalanceApi.BalanceDetail get(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.get(actor, id);
	}

	@Operation(summary = "Add a balance")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "validation_error",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping({ "/api/balances", "/api/admin/balances" })
	public ResponseEntity<BalanceApi.BalanceDetail> create(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody BalanceApi.BalanceRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.service.create(actor, body));
	}

	@Operation(summary = "Change a balance", description = "Full replace. The amount is changed with fix.")
	@PutMapping({ "/api/balances/{id}", "/api/admin/balances/{id}" })
	public BalanceApi.BalanceDetail update(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody BalanceApi.BalanceRequest body) {
		return this.service.update(actor, id, body);
	}

	@Operation(summary = "Move a balance to the trash", description = "Hidden from the list, the dashboard, the job "
			+ "and the alerts; its alerts are removed. Its history and the expenses stay. Only the OWNER sees the trash "
			+ "and restores.")
	@ApiResponse(responseCode = "204", description = "In the trash")
	@DeleteMapping({ "/api/balances/{id}", "/api/admin/balances/{id}" })
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		this.service.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Recharge", description = "Adds one company expense (with the proof) and a new start point "
			+ "= guess on that date + amount.")
	@ApiResponse(responseCode = "400", description = "validation_error, proof_required, future_date",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping({ "/api/balances/{id}/recharge", "/api/admin/balances/{id}/recharge" })
	public BalanceApi.BalanceDetail recharge(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody BalanceApi.RechargeRequest body) {
		return this.service.recharge(actor, id, body);
	}

	@Operation(summary = "Fix the real balance", description = "New start point = the amount, today. No expense.")
	@PostMapping({ "/api/balances/{id}/fix", "/api/admin/balances/{id}/fix" })
	public BalanceApi.BalanceDetail fix(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody BalanceApi.FixRequest body) {
		return this.service.fix(actor, id, body);
	}

	@Operation(summary = "The balance trash (OWNER)", description = "Last trashed first, with deletedAt and deletedBy.")
	@GetMapping("/api/admin/balances/trash")
	public List<BalanceApi.Balance> trashList(@AuthenticationPrincipal AuthUser actor) {
		return this.service.trashList(actor);
	}

	@Operation(summary = "Restore a balance from the trash (OWNER)",
			description = "With its settings and history. The guess goes on from its start point.")
	@ApiResponse(responseCode = "200", description = "Restored")
	@ApiResponse(responseCode = "404", description = "balance_not_found",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@PostMapping("/api/admin/balances/{id}/restore")
	public BalanceApi.BalanceDetail restore(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id) {
		return this.service.restore(actor, id);
	}

}
