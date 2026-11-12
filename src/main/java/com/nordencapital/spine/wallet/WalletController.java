package com.nordencapital.spine.wallet;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ListFilter;
import com.nordencapital.spine.common.ListPage;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.ParentPatch;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.transaction.TransactionRequest;
import com.nordencapital.spine.user.User;

/**
 * The private wallet. Strictly mine: OWNER and ADMIN have no access, and other users'
 * items are "not found". Everything except status, PIN and unlock needs the wallet to
 * be unlocked in this session (423 {@code wallet_locked}). See docs/api-module5.md (G).
 */
@Tag(name = "Wallet", description = "My private wallet. Unlock with the PIN first (423 wallet_locked otherwise). "
		+ "Nobody else can see it, not even the owner or admins.")
@RestController
public class WalletController {

	private final WalletLock lock;

	private final WalletCategories categories;

	private final WalletTags tags;

	private final WalletTransactions transactions;

	private final WalletBudgets budgets;

	private final WalletGoals goals;

	private final WalletRecurring recurring;

	public WalletController(WalletLock lock, WalletCategories categories, WalletTags tags,
			WalletTransactions transactions, WalletBudgets budgets, WalletGoals goals, WalletRecurring recurring) {
		this.lock = lock;
		this.categories = categories;
		this.tags = tags;
		this.transactions = transactions;
		this.budgets = budgets;
		this.goals = goals;
		this.recurring = recurring;
	}

	// ---- PIN and lock ----

	@Operation(summary = "Wallet status", description = "Always allowed.")
	@GetMapping("/api/wallet/status")
	public WalletLock.Status status(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.lock.status(actor, request.getSession(false));
	}

	@Operation(summary = "Set my first PIN (6 digits)", description = "Unlocks the wallet and makes starting categories. "
			+ "409 pin_exists.")
	@PostMapping("/api/wallet/pin")
	public ResponseEntity<Void> setPin(@AuthenticationPrincipal AuthUser actor, @RequestBody PinRequest body,
			HttpServletRequest request) {
		this.lock.setPin(actor, request.getSession(), body.pin());
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Unlock the wallet",
			description = "15 minutes, extended by each wallet request. 400 invalid_pin; 5 wrong = 429 for 5 minutes.")
	@PostMapping("/api/wallet/unlock")
	public ResponseEntity<Void> unlock(@AuthenticationPrincipal AuthUser actor, @RequestBody PinRequest body,
			HttpServletRequest request) {
		this.lock.unlock(actor, request.getSession(), body.pin());
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Lock the wallet now")
	@PostMapping("/api/wallet/lock")
	public ResponseEntity<Void> lockNow(HttpServletRequest request) {
		this.lock.lock(request.getSession(false));
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "New PIN with my account password", description = "400 invalid_password; 429 too_many_attempts.")
	@PostMapping("/api/wallet/pin/reset")
	public ResponseEntity<Void> resetPin(@AuthenticationPrincipal AuthUser actor, @RequestBody PinResetRequest body,
			HttpServletRequest request) {
		this.lock.resetPin(actor, request.getSession(), body.password(), body.newPin());
		return ResponseEntity.noContent().build();
	}

	// ---- dashboard ----

	@Operation(summary = "Wallet dashboard",
			description = "Balance (all time, per currency), this month, budgets, goals, 5 latest transactions.")
	@GetMapping("/api/wallet/dashboard")
	public Dashboard dashboard(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		User user = me(actor, request);
		YearMonth month = this.budgets.thisMonth(user.getId());
		return new Dashboard(this.transactions.balance(user.getId()),
				this.transactions.totals(user.getId(), month.atDay(1), month.atEndOfMonth()),
				this.budgets.list(user.getId()), this.goals.list(user.getId()), this.transactions.recent(user.getId(), 5));
	}

	// ---- categories ----

	@Operation(summary = "My categories for a picker", description = "Most used in 90 days first, then by name.")
	@GetMapping("/api/wallet/categories")
	public List<WalletCategories.Pick> categories(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.categories.picker(me(actor, request).getId());
	}

	@Operation(summary = "All my categories, archived too")
	@GetMapping("/api/wallet/categories/all")
	public List<WalletCategories.Pick> allCategories(@AuthenticationPrincipal AuthUser actor,
			HttpServletRequest request) {
		return this.categories.allPicks(me(actor, request).getId());
	}

	@Operation(summary = "Add a category", description = "409 name_taken.")
	@PostMapping("/api/wallet/categories")
	public ResponseEntity<WalletCategories.Pick> createCategory(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody CategoryRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.categories.create(me(actor, request).getId(), body.name(), body.icon(), body.parentId()));
	}

	@Operation(summary = "Change a category", description = "parentId: leave out = keep, null = top level.")
	@PatchMapping("/api/wallet/categories/{id}")
	public WalletCategories.Pick updateCategory(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody CategoryUpdate body, HttpServletRequest request) {
		return this.categories.update(me(actor, request).getId(), id, body.name(), body.icon(),
				ParentPatch.of(body.parentId()));
	}

	@PostMapping("/api/wallet/categories/{id}/archive")
	public WalletCategories.Pick archiveCategory(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.categories.setArchived(me(actor, request).getId(), id, true);
	}

	@PostMapping("/api/wallet/categories/{id}/unarchive")
	public WalletCategories.Pick unarchiveCategory(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.categories.setArchived(me(actor, request).getId(), id, false);
	}

	// ---- tags ----

	@Operation(summary = "My tags for a picker", description = "Most used in 90 days first, then by name, max 50.")
	@GetMapping("/api/wallet/tags")
	public List<WalletTags.Pick> tags(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String q, HttpServletRequest request) {
		return this.tags.search(me(actor, request).getId(), q);
	}

	@GetMapping("/api/wallet/tags/all")
	public List<WalletTags.Pick> allTags(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.tags.all(me(actor, request).getId());
	}

	@Operation(summary = "Add a tag", description = "201 new, 200 existing active, 409 tag_archived.")
	@PostMapping("/api/wallet/tags")
	public ResponseEntity<WalletTags.Pick> createTag(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody NameRequest body, HttpServletRequest request) {
		WalletTags.Created created = this.tags.create(me(actor, request).getId(), body.name());
		return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.tag());
	}

	@PatchMapping("/api/wallet/tags/{id}")
	public WalletTags.Pick renameTag(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody NameRequest body, HttpServletRequest request) {
		return this.tags.rename(me(actor, request).getId(), id, body.name());
	}

	@PostMapping("/api/wallet/tags/{id}/archive")
	public WalletTags.Pick archiveTag(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.tags.setArchived(me(actor, request).getId(), id, true);
	}

	@PostMapping("/api/wallet/tags/{id}/unarchive")
	public WalletTags.Pick unarchiveTag(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.tags.setArchived(me(actor, request).getId(), id, false);
	}

	// ---- transactions ----

	@Operation(summary = "Search my wallet transactions", description = "Same filters and paging as company list "
			+ "(no createdBy, q only in descriptions).")
	@GetMapping("/api/wallet/transactions")
	public ListPage<WalletTransactions.WalletTx> listTransactions(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String month, @RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> categoryIds,
			@RequestParam(required = false) List<String> tagIds, @RequestParam(required = false) String type,
			@RequestParam(required = false) String currency, @RequestParam(required = false) String minAmount,
			@RequestParam(required = false) String maxAmount, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, HttpServletRequest request) {
		return this.transactions.list(me(actor, request), new ListFilter.Params(from, to, month, q, categoryIds, tagIds,
				type, currency, null, minAmount, maxAmount, page, size));
	}

	@GetMapping("/api/wallet/transactions/{id}")
	public WalletTransactions.WalletTx getTransaction(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.transactions.get(me(actor, request), id);
	}

	@Operation(summary = "Add a wallet transaction", description = "Files optional (0-5). 409 possible_duplicate "
			+ "unless confirmDuplicate is true.")
	@PostMapping("/api/wallet/transactions")
	public ResponseEntity<WalletTransactions.WalletTx> createTransaction(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody TransactionRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.transactions.create(me(actor, request), body));
	}

	@Operation(summary = "Change a wallet transaction (send all fields)")
	@PutMapping("/api/wallet/transactions/{id}")
	public WalletTransactions.WalletTx updateTransaction(@AuthenticationPrincipal AuthUser actor,
			@PathVariable UUID id, @Valid @RequestBody TransactionRequest body, HttpServletRequest request) {
		return this.transactions.update(me(actor, request), id, body);
	}

	@Operation(summary = "Delete a wallet transaction", description = "Hard delete, with its files.")
	@DeleteMapping("/api/wallet/transactions/{id}")
	public ResponseEntity<Void> deleteTransaction(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.transactions.delete(me(actor, request), id);
		return ResponseEntity.noContent().build();
	}

	// ---- budgets ----

	@GetMapping("/api/wallet/budgets")
	public List<WalletBudgets.Budget> budgets(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.budgets.list(me(actor, request).getId());
	}

	@Operation(summary = "Add a monthly budget", description = "No categoryId = total for the currency. "
			+ "409 budget_exists.")
	@PostMapping("/api/wallet/budgets")
	public ResponseEntity<WalletBudgets.Budget> createBudget(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletBudgets.BudgetRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.budgets.create(me(actor, request).getId(), body));
	}

	@PutMapping("/api/wallet/budgets/{id}")
	public WalletBudgets.Budget updateBudget(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletBudgets.BudgetRequest body, HttpServletRequest request) {
		return this.budgets.update(me(actor, request).getId(), id, body);
	}

	@DeleteMapping("/api/wallet/budgets/{id}")
	public ResponseEntity<Void> deleteBudget(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.budgets.delete(me(actor, request).getId(), id);
		return ResponseEntity.noContent().build();
	}

	// ---- goals ----

	@GetMapping("/api/wallet/goals")
	public List<WalletGoals.Goal> goals(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.goals.list(me(actor, request).getId());
	}

	@PostMapping("/api/wallet/goals")
	public ResponseEntity<WalletGoals.Goal> createGoal(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletGoals.GoalRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.goals.create(me(actor, request).getId(), body));
	}

	@PutMapping("/api/wallet/goals/{id}")
	public WalletGoals.Goal updateGoal(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletGoals.GoalRequest body, HttpServletRequest request) {
		return this.goals.update(me(actor, request).getId(), id, body);
	}

	@DeleteMapping("/api/wallet/goals/{id}")
	public ResponseEntity<Void> deleteGoal(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.goals.delete(me(actor, request).getId(), id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Add money to a goal", description = "Returns the whole goal. Does not change the balance.")
	@PostMapping("/api/wallet/goals/{id}/contributions")
	public ResponseEntity<WalletGoals.Goal> contribute(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletGoals.ContributionRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.goals.contribute(me(actor, request).getId(), id, body));
	}

	@DeleteMapping("/api/wallet/goals/{id}/contributions/{contributionId}")
	public WalletGoals.Goal removeContribution(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@PathVariable UUID contributionId, HttpServletRequest request) {
		return this.goals.removeContribution(me(actor, request).getId(), id, contributionId);
	}

	// ---- recurring ----

	@GetMapping("/api/wallet/recurring")
	public List<WalletRecurring.Recurring> recurring(@AuthenticationPrincipal AuthUser actor,
			HttpServletRequest request) {
		return this.recurring.list(me(actor, request).getId());
	}

	@Operation(summary = "Add a recurring item", description = "nextDate = first date on or after today; "
			+ "if that is today, the transaction is made at once.")
	@PostMapping("/api/wallet/recurring")
	public ResponseEntity<WalletRecurring.Recurring> createRecurring(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletRecurring.RecurringRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.recurring.create(me(actor, request).getId(), body));
	}

	@PutMapping("/api/wallet/recurring/{id}")
	public WalletRecurring.Recurring updateRecurring(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletRecurring.RecurringRequest body, HttpServletRequest request) {
		return this.recurring.update(me(actor, request).getId(), id, body);
	}

	@DeleteMapping("/api/wallet/recurring/{id}")
	public ResponseEntity<Void> deleteRecurring(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.recurring.delete(me(actor, request).getId(), id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/wallet/recurring/{id}/pause")
	public WalletRecurring.Recurring pause(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.recurring.setActive(me(actor, request).getId(), id, false);
	}

	@Operation(summary = "Resume", description = "Missed dates are not made; nextDate = next on or after today.")
	@PostMapping("/api/wallet/recurring/{id}/resume")
	public WalletRecurring.Recurring resume(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.recurring.setActive(me(actor, request).getId(), id, true);
	}

	/** The caller, with the wallet unlocked (else 423 / 409). */
	private User me(AuthUser actor, HttpServletRequest request) {
		return this.lock.requireUnlocked(actor, request.getSession(false));
	}

	// ---- bodies and responses ----

	public record PinRequest(@Schema(example = "123456") String pin) {
	}

	public record PinResetRequest(String password, @Schema(example = "654321") String newPin) {
	}

	/** The name is trimmed before validation. */
	public record NameRequest(@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
			message = "must not contain control characters") String name) {

		public NameRequest {
			name = Names.clean(name);
		}

	}

	/** The name is trimmed before validation. */
	public record CategoryRequest(
			@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@NotNull String icon, UUID parentId) {

		public CategoryRequest {
			name = Names.clean(name);
		}

	}

	/** All optional. parentId: leave out = keep, null = top level. */
	public record CategoryUpdate(
			@Size(min = 1, max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			String icon, @Schema(type = "string", format = "uuid", nullable = true) JsonNode parentId) {

		public CategoryUpdate {
			name = Names.clean(name);
		}

	}

	/**
	 * @param balance all-time INCOME - EXPENSE per currency
	 * @param thisMonth this month in/out/net per currency
	 */
	public record Dashboard(Map<String, String> balance, Map<String, Totals> thisMonth,
			List<WalletBudgets.Budget> budgets, List<WalletGoals.Goal> goals, List<WalletTransactions.WalletTx> recent) {
	}

}
