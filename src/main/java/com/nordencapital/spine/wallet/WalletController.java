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
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.ParentPatch;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.user.User;

/**
 * The private wallet. Strictly mine: even the OWNER has no access, and other users'
 * items are "not found". Everything except status, PIN and unlock needs the wallet to
 * be unlocked in this session (423 {@code wallet_locked}). See docs/api-module5.md (G).
 */
@Tag(name = "Wallet", description = "My private wallet. Unlock with the PIN first (423 wallet_locked otherwise). "
		+ "Nobody else can see it, not even the owner or admins.")
@RestController
public class WalletController {

	private final WalletLock lock;

	private final WalletCategories categories;

	private final WalletTransactions transactions;

	private final WalletBudgets budgets;

	private final WalletGoals goals;

	private final WalletRecurring recurring;

	private final WalletMoney money;

	public WalletController(WalletLock lock, WalletCategories categories,
			WalletTransactions transactions, WalletBudgets budgets, WalletGoals goals, WalletRecurring recurring,
			WalletMoney money) {
		this.lock = lock;
		this.money = money;
		this.categories = categories;
		this.transactions = transactions;
		this.budgets = budgets;
		this.goals = goals;
		this.recurring = recurring;
	}

	// ---- PIN and lock ----

	@Operation(summary = "Wallet status", description = "Always allowed. passkey = I have at least one passkey "
			+ "(offer Face ID unlock).")
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

	@Operation(summary = "Start a Face ID unlock",
			description = "Returns PublicKeyCredentialRequestOptionsJSON, same shape as POST /api/auth/passkey/options, "
					+ "but allowCredentials = only my passkeys and userVerification = required. Valid 5 minutes, once. "
					+ "409 no_pin.")
	@PostMapping(path = "/api/wallet/unlock/passkey/options",
			produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
	public String unlockPasskeyOptions(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.lock.passkeyOptions(actor, request.getSession());
	}

	@Operation(summary = "Unlock the wallet with Face ID (a passkey)",
			description = "Body: {\"credential\": AuthenticationResponseJSON} (credential.toJSON()), same as passkey "
					+ "login. The passkey must be mine. Same effect as a correct PIN (15 minutes, wrong-PIN count reset; "
					+ "it also ends a wrong-PIN lock). 400 passkey_failed (not counted as a wrong PIN); 429 "
					+ "too_many_attempts after 5 failures; 409 no_pin.")
	@PostMapping("/api/wallet/unlock/passkey")
	public ResponseEntity<Void> unlockWithPasskey(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody PasskeyUnlockRequest body, HttpServletRequest request) {
		this.lock.unlockWithPasskey(actor, request.getSession(), body.credential().toString());
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
			description = "Balance (all time income - expense; goal money is already in it as transactions), this "
					+ "month in/out/net (goal transactions count), budgets, goals, 5 latest transactions. "
					+ "balance, savedInGoals and thisMonth are in my default wallet currency (`currency`), each item "
					+ "converted with my rate (`usdRate`: 1 USD = usdRate MAD). Budgets, goals and transactions keep "
					+ "their own currency.")
	@GetMapping("/api/wallet/dashboard")
	public Dashboard dashboard(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		User user = me(actor, request);
		YearMonth month = this.budgets.thisMonth(user.getId());
		WalletMoney.Settings settings = this.money.settings(user.getId());
		java.math.BigDecimal net = this.transactions.balance(user.getId(), settings);
		java.math.BigDecimal saved = this.goals.savedTotal(user.getId(), settings);
		return new Dashboard(Totals.money(net), Totals.money(saved),
				this.transactions.totals(user.getId(), month.atDay(1), month.atEndOfMonth(), settings),
				settings.currency(), settings.usdRateText(),
				this.budgets.list(user.getId()), this.goals.list(user.getId()), this.transactions.recent(user.getId(), 5));
	}

	// ---- categories ----

	@Operation(summary = "My categories for a picker", description = "Most used in 90 days first, then by name.")
	@GetMapping("/api/wallet/categories")
	public List<WalletCategories.Pick> categories(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String kind, HttpServletRequest request) {
		return this.categories.picker(me(actor, request).getId(), kind);
	}

	@Operation(summary = "All my categories, archived too",
			description = "Manage-page order: each main category by position, then its subcategories by position.")
	@GetMapping("/api/wallet/categories/all")
	public List<WalletCategories.Pick> allCategories(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String kind, HttpServletRequest request) {
		return this.categories.allPicks(me(actor, request).getId(), kind);
	}

	@Operation(summary = "Add a category", description = "409 name_taken.")
	@PostMapping("/api/wallet/categories")
	public ResponseEntity<WalletCategories.Pick> createCategory(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody CategoryRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.categories.create(me(actor, request).getId(), body.name(), body.icon(), body.parentId(),
					body.website(), body.kind()));
	}

	@Operation(summary = "Change a category",
			description = "parentId: leave out = keep, null = top level. website: leave out = keep, null or \"\" = "
					+ "remove.")
	@PatchMapping("/api/wallet/categories/{id}")
	public WalletCategories.Pick updateCategory(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody CategoryUpdate body, HttpServletRequest request) {
		return this.categories.update(me(actor, request).getId(), id, body.name(), body.icon(),
				ParentPatch.of(body.parentId()), Websites.patch(body.website()), body.kind());
	}

	@Operation(summary = "Move one of my categories (drag and drop)",
			description = "Same as PATCH /api/admin/categories/{id}/move, for my wallet. Returns ALL my categories in "
					+ "manage-page order.")
	@PatchMapping("/api/wallet/categories/{id}/move")
	public List<WalletCategories.Pick> moveCategory(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody com.nordencapital.spine.category.CategoryController.MoveRequest body,
			HttpServletRequest request) {
		return this.categories.move(me(actor, request).getId(), id, body.parentId(), body.position());
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

	// ---- transactions ----

	@Operation(summary = "Search my wallet transactions", description = "Same filters and paging as company list "
			+ "(no createdBy, q only in descriptions). minAmount/maxAmount compare each item's own amount. totals are "
			+ "in my default wallet currency (`currency`), converted with my rate (`usdRate`: 1 USD = usdRate MAD).")
	@GetMapping("/api/wallet/transactions")
	public WalletTransactions.WalletPage listTransactions(@AuthenticationPrincipal AuthUser actor,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String month, @RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> categoryIds,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String minAmount,
			@RequestParam(required = false) String maxAmount, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, HttpServletRequest request) {
		return this.transactions.list(me(actor, request), new ListFilter.Params(from, to, month, q, categoryIds,
				type, null, minAmount, maxAmount, page, size));
	}

	@GetMapping("/api/wallet/transactions/{id}")
	public WalletTransactions.WalletTx getTransaction(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		return this.transactions.get(me(actor, request), id);
	}

	@Operation(summary = "Add a wallet transaction", description = "Files optional (0-5). currency USD or MAD "
			+ "(left out = my default wallet currency). 409 possible_duplicate (same type, amount, currency and date) "
			+ "unless confirmDuplicate is true.")
	@PostMapping("/api/wallet/transactions")
	public ResponseEntity<WalletTransactions.WalletTx> createTransaction(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletTransactionRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.transactions.create(me(actor, request), body));
	}

	@Operation(summary = "Change a wallet transaction (send all fields)",
			description = "currency left out = keep the transaction's currency. 409 goal_money when goalId is set "
					+ "(made by a goal movement: change it on the Goals page).")
	@PutMapping("/api/wallet/transactions/{id}")
	public WalletTransactions.WalletTx updateTransaction(@AuthenticationPrincipal AuthUser actor,
			@PathVariable UUID id, @Valid @RequestBody WalletTransactionRequest body, HttpServletRequest request) {
		return this.transactions.update(me(actor, request), id, body);
	}

	@Operation(summary = "Delete a wallet transaction", description = "Hard delete, with its files. If a "
			+ "recurring item made it, that date becomes a skipped date of the item (it is not made again). If a goal "
			+ "movement made it (goalId set), the movement is removed too, like its undo (409 not_enough_saved when "
			+ "removing an add would leave the goal below 0).")
	@DeleteMapping("/api/wallet/transactions/{id}")
	public ResponseEntity<Void> deleteTransaction(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.transactions.delete(me(actor, request), id);
		return ResponseEntity.noContent().build();
	}

	// ---- budgets ----

	@Operation(summary = "My monthly budgets", description = "Each budget has its own currency; spent = this month's "
			+ "expenses converted into the budget's currency with my rate. Goal money (transactions with goalId) "
			+ "does not count.")
	@GetMapping("/api/wallet/budgets")
	public List<WalletBudgets.Budget> budgets(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.budgets.list(me(actor, request).getId());
	}

	@Operation(summary = "Add a monthly budget", description = "No categoryId = total budget. currency USD or MAD "
			+ "(left out = my default wallet currency). 409 budget_exists.")
	@PostMapping("/api/wallet/budgets")
	public ResponseEntity<WalletBudgets.Budget> createBudget(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletBudgets.BudgetRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.budgets.create(me(actor, request).getId(), body));
	}

	@Operation(summary = "Reorder my budgets", description = "Body: all my budget ids in the new order. Returns my "
			+ "budgets in that order. 400 validation_error if an id is missing, extra or repeated.")
	@PutMapping("/api/wallet/budgets/order")
	public List<WalletBudgets.Budget> reorderBudgets(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletBudgets.OrderRequest body, HttpServletRequest request) {
		return this.budgets.reorder(me(actor, request).getId(), body.ids());
	}

	@Operation(summary = "Change a budget", description = "currency left out = keep.")
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

	@Operation(summary = "My goals", description = "Each goal has its own currency (target, saved and movements "
			+ "are in it).")
	@GetMapping("/api/wallet/goals")
	public List<WalletGoals.Goal> goals(@AuthenticationPrincipal AuthUser actor, HttpServletRequest request) {
		return this.goals.list(me(actor, request).getId());
	}

	@Operation(summary = "Add a goal", description = "currency USD or MAD (left out = my default wallet currency).")
	@PostMapping("/api/wallet/goals")
	public ResponseEntity<WalletGoals.Goal> createGoal(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletGoals.GoalRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.goals.create(me(actor, request).getId(), body));
	}

	@Operation(summary = "Reorder my goals", description = "Body: all my goal ids in the new order. Returns my goals "
			+ "in that order. 400 validation_error if an id is missing, extra or repeated.")
	@PutMapping("/api/wallet/goals/order")
	public List<WalletGoals.Goal> reorderGoals(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletGoals.OrderRequest body, HttpServletRequest request) {
		return this.goals.reorder(me(actor, request).getId(), body.ids());
	}

	@Operation(summary = "Change a goal", description = "currency left out = keep. A new currency also applies to "
			+ "the money already in the goal (not converted).")
	@PutMapping("/api/wallet/goals/{id}")
	public WalletGoals.Goal updateGoal(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletGoals.GoalRequest body, HttpServletRequest request) {
		return this.goals.update(me(actor, request).getId(), id, body);
	}

	@Operation(summary = "Delete a goal", description = "Also deletes its movements and their transactions (the "
			+ "money is back in the balance).")
	@DeleteMapping("/api/wallet/goals/{id}")
	public ResponseEntity<Void> deleteGoal(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			HttpServletRequest request) {
		this.goals.delete(me(actor, request).getId(), id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Add money to a goal", description = "Returns the whole goal. amount is in the goal's "
			+ "currency. Makes a wallet EXPENSE in my \"Goals\" category (made when missing): same amount, goal "
			+ "currency, movement date, description \"Goal: <name>\" (the note stays on the movement). It counts in "
			+ "this month's out and list totals, not in budgets.")
	@PostMapping("/api/wallet/goals/{id}/contributions")
	public ResponseEntity<WalletGoals.Goal> contribute(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletGoals.ContributionRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.goals.contribute(me(actor, request).getId(), id, body));
	}

	@Operation(summary = "Take money out of a goal", description = "Body like Add money. Makes a wallet INCOME in my "
			+ "\"From goals\" category (made when missing). 409 not_enough_saved when the goal holds less.")
	@PostMapping("/api/wallet/goals/{id}/take-out")
	public ResponseEntity<WalletGoals.Goal> takeOut(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody WalletGoals.ContributionRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.goals.takeOut(me(actor, request).getId(), id, body));
	}

	@Operation(summary = "Remove one goal movement", description = "Undoes an add or a take out and deletes its "
			+ "transaction (the balance follows). 409 not_enough_saved if removing an add would leave the goal below 0.")
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
			+ "if that is today, the transaction is made at once. currency USD or MAD (left out = my default wallet "
			+ "currency); its transactions are made in it.")
	@PostMapping("/api/wallet/recurring")
	public ResponseEntity<WalletRecurring.Recurring> createRecurring(@AuthenticationPrincipal AuthUser actor,
			@Valid @RequestBody WalletRecurring.RecurringRequest body, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.recurring.create(me(actor, request).getId(), body));
	}

	@Operation(summary = "Change a recurring item", description = "currency left out = keep. Transactions already "
			+ "made keep their currency.")
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

	@Operation(summary = "Pause until a date", description = "Nothing is made before that date; then it goes on "
			+ "by itself. Resume ends it early. Body: {\"date\": \"2026-12-01\"} (after today).")
	@PostMapping("/api/wallet/recurring/{id}/pause-until")
	public WalletRecurring.Recurring pauseRecurringUntil(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody com.nordencapital.spine.common.Recurrence.DateRequest body, HttpServletRequest request) {
		return this.recurring.pauseUntil(me(actor, request).getId(), id, body.date());
	}

	@Operation(summary = "Skip one date", description = "That transaction is not made; the next ones are normal. "
			+ "Body: {\"date\": \"2026-12-01\"} (a date of the series, today or later). 409 already_created.")
	@PostMapping("/api/wallet/recurring/{id}/skips")
	public WalletRecurring.Recurring skipRecurring(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@Valid @RequestBody com.nordencapital.spine.common.Recurrence.DateRequest body, HttpServletRequest request) {
		return this.recurring.skip(me(actor, request).getId(), id, body.date());
	}

	@Operation(summary = "Undo a skip", description = "Only before its date (409 too_late).")
	@DeleteMapping("/api/wallet/recurring/{id}/skips/{date}")
	public WalletRecurring.Recurring unskipRecurring(@AuthenticationPrincipal AuthUser actor, @PathVariable UUID id,
			@PathVariable String date, HttpServletRequest request) {
		return this.recurring.unskip(me(actor, request).getId(), id, date);
	}

	@Operation(summary = "Resume", description = "Ends a pause (with or without a date). Missed dates are "
			+ "not made; nextDate = next on or after today.")
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

	public record PasskeyUnlockRequest(
			@Schema(description = "AuthenticationResponseJSON (credential.toJSON())") @NotNull JsonNode credential) {
	}

	public record PinResetRequest(String password, @Schema(example = "654321") String newPin) {
	}

	/** The name is trimmed before validation. */
	public record CategoryRequest(
			@NotBlank @Size(max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@NotNull String icon, UUID parentId,
			@Schema(nullable = true, description = "Optional website (logo), e.g. \"claude.ai\"")
			@Size(max = Websites.MAX_INPUT) String website,
			@Schema(nullable = true, allowableValues = { "EXPENSE", "INCOME" },
					description = "Main: default EXPENSE. Sub: its main's kind") String kind) {

		public CategoryRequest {
			name = Names.clean(name);
		}

	}

	/** All optional. parentId: leave out = keep, null = top level. */
	public record CategoryUpdate(
			@Size(min = 1, max = Names.MAX_LENGTH) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			String icon, @Schema(type = "string", format = "uuid", nullable = true) JsonNode parentId,
			@Schema(type = "string", nullable = true, description = "Leave out = keep; null or \"\" = remove")
			JsonNode website,
			@Schema(nullable = true, allowableValues = { "EXPENSE", "INCOME" },
					description = "Main categories only (subs follow). Leave out = keep") String kind) {

		public CategoryUpdate {
			name = Names.clean(name);
		}

	}

	/**
	 * @param balance available now: all-time INCOME - EXPENSE (goal adds are EXPENSE and take outs
	 * INCOME transactions, so goal money is already out of it)
	 * @param savedInGoals all money now in my goals (information only, not subtracted again)
	 * @param thisMonth this month in/out/net of all transactions (goal transactions too)
	 * @param currency my default wallet currency: balance, savedInGoals and thisMonth are in it
	 * @param usdRate my rate used for them, 1 USD = usdRate MAD, e.g. "10.00"
	 */
	public record Dashboard(String balance, String savedInGoals, Totals thisMonth, String currency, String usdRate,
			List<WalletBudgets.Budget> budgets, List<WalletGoals.Goal> goals, List<WalletTransactions.WalletTx> recent) {
	}

}
