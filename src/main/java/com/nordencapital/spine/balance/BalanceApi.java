package com.nordencapital.spine.balance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;

/** The JSON of /api/balances and /api/admin/balances. */
public final class BalanceApi {

	private BalanceApi() {
	}

	/**
	 * One balance with Spine's guess for today (Morocco day).
	 * @param unit DAY, WEEK, MONTH, YEAR (every N) or TIMES_A_MONTH (N times a month)
	 * @param nextCut the next cut date after today
	 * @param startAmount the start point: balance at the end of startDate (last create, recharge or fix)
	 * @param guess the guessed balance today, never below 0
	 * @param percent guess / startAmount, 0 to 100 (for the bar)
	 * @param perMonth cost a month
	 * @param emptyDate the cut date that takes the guess to $0 (in the past or today when empty); null = too far
	 * @param daysLeft days from today to emptyDate (0 or less when empty), null with emptyDate null
	 * @param lowFrom the first date the guess is under lowLimit (null without a limit)
	 * @param urgency RED (empty or under the limit), AMBER (empty within warnDays, or the next cut goes under
	 * the limit), GREEN
	 * @param canEdit true for the OWNER
	 */
	public record Balance(UUID id, String name, String website, List<String> logoUrls, CategoryRef category,
			String cut, String unit, int every, LocalDate nextCut, String lowLimit, int warnDays, String note,
			String startAmount, LocalDate startDate, String guess, int percent, String perMonth, LocalDate emptyDate,
			Long daysLeft, boolean empty, LocalDate lowFrom, String urgency, UserRef createdBy, Instant createdAt,
			UserRef updatedBy, Instant updatedAt, boolean canEdit) {
	}

	/**
	 * @param perMonth all balances' cost a month
	 * @param needRecharge how many are RED or AMBER
	 */
	public record Summary(int count, String perMonth, int needRecharge, int red, int amber) {
	}

	/** @param items most urgent first */
	public record BalanceList(Summary summary, List<Balance> items) {
	}

	/** @param history newest first */
	public record BalanceDetail(Balance balance, List<HistoryItem> history) {
	}

	/**
	 * A history line. CREATED / RECHARGE / FIX: amount (start amount, money added, real balance typed),
	 * guessBefore (RECHARGE, FIX), startAfter, by; RECHARGE also transactionId (the expense). CUTS: the cuts
	 * Spine guessed between two events (or up to today): count, from / to (first and last cut date), each,
	 * total (never more than the start amount).
	 * @param type CREATED, RECHARGE, FIX or CUTS
	 * @param date the event date (CUTS: the last cut date)
	 */
	public record HistoryItem(String type, LocalDate date, String amount, String guessBefore, String startAfter,
			UUID transactionId, UserRef by, Long count, LocalDate from, LocalDate to, String each, String total) {
	}

	/** Body of POST and PUT /api/admin/balances. Text is trimmed. */
	public record BalanceRequest(
			@NotBlank @Size(max = 60) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@Schema(nullable = true, example = "twilio.com") @Size(max = Websites.MAX_INPUT) String website,
			@Schema(description = "A company EXPENSE category") @NotNull UUID categoryId,
			@Schema(description = "Each cut, e.g. \"12.00\"") @NotNull String cut,
			@NotNull @Pattern(regexp = "DAY|WEEK|MONTH|YEAR|TIMES_A_MONTH",
					message = "must be DAY, WEEK, MONTH, YEAR or TIMES_A_MONTH")
			@Schema(allowableValues = { "DAY", "WEEK", "MONTH", "YEAR", "TIMES_A_MONTH" }) String unit,
			@Schema(description = "Every N units (DAY 1-365, WEEK 1-52, MONTH 1-24, YEAR 1-5) or N times a month "
					+ "(1-31)", example = "1") @NotNull Integer every,
			@Schema(nullable = true, description = "The next cut date, after today. Empty = one step after today "
					+ "(edit: the series is kept)") @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}",
							message = "must be like 2026-03-31") String nextCut,
			@Schema(nullable = true, description = "Create only (required): the balance now, 0 or more. Ignored on "
					+ "edit: use fix.") String startAmount,
			@Schema(nullable = true, description = "Optional low limit, 0 or more") String lowLimit,
			@Schema(nullable = true, description = "Warn this many days before empty (0-365, default 7)")
			Integer warnDays,
			@Schema(nullable = true) @Size(max = 500) String note) {

		public BalanceRequest {
			name = (name != null) ? name.strip() : null;
		}

	}

	/** Body of POST /api/admin/balances/{id}/recharge. */
	public record RechargeRequest(
			@Schema(description = "Money added, e.g. \"50.00\"") @NotNull String amount,
			@Schema(description = "YYYY-MM-DD, not in the future, not before the last recharge or fix") @NotNull
			@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
			@Schema(description = "REQUIRED: 1 to 5 proof files you just uploaded") List<UUID> fileIds,
			@Schema(nullable = true, description = "Optional note, added as a comment on the expense")
			@Size(max = 500) String note) {
	}

	/** Body of POST /api/admin/balances/{id}/fix. */
	public record FixRequest(@Schema(description = "The real balance now, 0 or more") @NotNull String amount) {
	}

}
