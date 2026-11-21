package com.nordencapital.spine.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.me.UserZones;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;

/**
 * Monthly budgets: a limit in the budget's own currency (USD or MAD), either for everything
 * (no category) or for one category and its subcategories. "Spent" = this month's expenses
 * converted into the budget's currency with my rate. Alerts at 80% and 100%, once per budget
 * per month. Each month keeps its own limit (table wallet_budget_limits): the limit of a month is
 * the one set in that month or the last one before it; months before the first one use the
 * first one.
 */
@Component
public class WalletBudgets {

	private final JdbcTemplate jdbc;

	private final WalletCategories categories;

	private final AlertService alerts;

	private final UserZones zones;

	private final WalletMoney money;

	private final Clock clock;

	public WalletBudgets(JdbcTemplate jdbc, WalletCategories categories, AlertService alerts, UserZones zones,
			WalletMoney money, Clock clock) {
		this.jdbc = jdbc;
		this.money = money;
		this.categories = categories;
		this.alerts = alerts;
		this.zones = zones;
		this.clock = clock;
	}

	/** My budgets with what I spent this month (in my time zone). */
	@Transactional(readOnly = true)
	public List<Budget> list(UUID userId) {
		YearMonth month = thisMonth(userId);
		List<Row> rows = rows(userId);
		WalletMoney.Settings settings = this.money.settings(userId);
		Map<UUID, WalletCategories.Category> categoriesById = this.categories.byIds(userId,
				rows.stream().map(Row::categoryId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
		return rows.stream().map((row) -> {
			BigDecimal spent = spentByMonth(userId, row, month, month, settings, (m) -> row.currency())
				.getOrDefault(month, BigDecimal.ZERO);
			int percent = percent(spent, row.limit());
			WalletCategories.Category category = (row.categoryId() != null) ? categoriesById.get(row.categoryId())
					: null;
			CategoryRef ref = (category != null) ? new CategoryRef(category.id(), category.name(), category.icon(),
					category.archived(), category.parentId(), category.kind()) : null;
			return new Budget(row.id(), Totals.money(row.limit()), row.currency(), ref, Totals.money(spent), percent,
					status(percent));
		}).toList();
	}

	/**
	 * One budget with its past months (newest first, at most 12, from the first month with an
	 * expense in its scope) and a summary of the newest up-to-6 of them.
	 * @throws ApiException 404 {@code budget_not_found}
	 */
	@Transactional(readOnly = true)
	public History history(UUID userId, UUID id) {
		mine(userId, id);
		Budget budget = one(userId, id);
		Row row = rows(userId).stream().filter((r) -> r.id().equals(id)).findFirst().orElseThrow();
		List<Limit> limits = this.jdbc.query(
				"SELECT month, amount, currency FROM wallet_budget_limits WHERE budget_id = ? ORDER BY month",
				(rs, n) -> new Limit(YearMonth.from(rs.getDate("month").toLocalDate()), rs.getBigDecimal("amount"),
						rs.getString("currency")),
				id);
		java.util.function.Function<YearMonth, Limit> limitIn = (m) -> limitOf(limits, m, row);
		YearMonth last = thisMonth(userId).minusMonths(1);
		YearMonth first = last.minusMonths(11);
		Map<YearMonth, BigDecimal> spentByMonth = spentByMonth(userId, row, first, last, this.money.settings(userId),
				(m) -> limitIn.apply(m).currency());
		List<BudgetMonth> months = new java.util.ArrayList<>();
		if (!spentByMonth.isEmpty()) {
			YearMonth start = java.util.Collections.min(spentByMonth.keySet());
			for (YearMonth m = last; !m.isBefore(start); m = m.minusMonths(1)) {
				Limit limit = limitIn.apply(m);
				BigDecimal spent = spentByMonth.getOrDefault(m, BigDecimal.ZERO);
				int percent = percent(spent, limit.amount());
				months.add(new BudgetMonth(m.toString(), Totals.money(spent), Totals.money(limit.amount()),
						limit.currency(), percent, status(percent)));
			}
		}
		return new History(budget, months, summary(months));
	}

	/** Over the newest up-to-6 months; null when there are none. */
	private static Summary summary(List<BudgetMonth> months) {
		List<BudgetMonth> recent = months.subList(0, Math.min(6, months.size()));
		if (recent.isEmpty()) {
			return null;
		}
		BigDecimal total = BigDecimal.ZERO;
		int underLimit = 0;
		BudgetMonth biggest = recent.get(0);
		for (BudgetMonth month : recent) {
			BigDecimal spent = new BigDecimal(month.spent());
			total = total.add(spent);
			if (spent.compareTo(new BigDecimal(month.limit())) <= 0) {
				underLimit++;
			}
			if (spent.compareTo(new BigDecimal(biggest.spent())) > 0) {
				biggest = month;
			}
		}
		BigDecimal average = total.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
		return new Summary(recent.size(), Totals.money(average), underLimit,
				new Biggest(biggest.month(), biggest.spent()));
	}

	/** The row set in {@code month} or the last one before it; before all rows, the first one. */
	private static Limit limitOf(List<Limit> limits, YearMonth month, Row row) {
		if (limits.isEmpty()) {
			return new Limit(month, row.limit(), row.currency());
		}
		Limit found = limits.get(0);
		for (Limit limit : limits) {
			if (!limit.month().isAfter(month)) {
				found = limit;
			}
		}
		return found;
	}

	/** Whole number (FLOOR), can be over 100. */
	private static int percent(BigDecimal spent, BigDecimal limit) {
		return spent.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.FLOOR).intValue();
	}

	private static String status(int percent) {
		return (percent >= 100) ? "EXCEEDED" : (percent >= 80) ? "WARNING" : "OK";
	}

	/** This month's limit = the budget's current amount and currency (earlier months keep theirs). */
	private void saveLimit(UUID userId, UUID id) {
		this.jdbc.update("INSERT INTO wallet_budget_limits (budget_id, month, amount, currency) "
				+ "SELECT id, ?, amount, currency FROM wallet_budgets WHERE id = ? AND user_id = ? "
				+ "ON CONFLICT (budget_id, month) DO UPDATE SET amount = EXCLUDED.amount, currency = EXCLUDED.currency",
				Date.valueOf(thisMonth(userId).atDay(1)), id, userId);
	}

	/**
	 * @throws ApiException 409 {@code budget_exists}, 400 {@code validation_error}
	 */
	@Transactional
	public Budget create(UUID userId, BudgetRequest request) {
		BigDecimal limit = Money.parse("limit", request.limit());
		checkCategory(userId, request.categoryId());
		String currency = WalletMoney.pick(request.currency(), this.money.settings(userId).currency());
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		try {
			// A new budget goes last.
			this.jdbc.update("INSERT INTO wallet_budgets (id, user_id, amount, currency, category_id, position, "
					+ "created_at, updated_at) SELECT ?, ?, ?, ?, ?, coalesce(max(position) + 1, 0), ?, ? "
					+ "FROM wallet_budgets WHERE user_id = ?", id, userId, limit, currency, request.categoryId(), now,
					now, userId);
		}
		catch (DuplicateKeyException ex) {
			throw exists();
		}
		saveLimit(userId, id);
		checkAlerts(userId);
		return one(userId, id);
	}

	/** Currency left out = keep. @throws ApiException 404 {@code budget_not_found}, 409 {@code budget_exists}, 400 */
	@Transactional
	public Budget update(UUID userId, UUID id, BudgetRequest request) {
		mine(userId, id);
		BigDecimal limit = Money.parse("limit", request.limit());
		checkCategory(userId, request.categoryId());
		try {
			this.jdbc.update("UPDATE wallet_budgets SET amount = ?, currency = coalesce(?, currency), category_id = ?, "
					+ "updated_at = ? WHERE id = ? AND user_id = ?", limit, request.currency(), request.categoryId(),
					Timestamp.from(this.clock.instant()), id, userId);
		}
		catch (DuplicateKeyException ex) {
			throw exists();
		}
		saveLimit(userId, id);
		checkAlerts(userId);
		return one(userId, id);
	}

	/**
	 * Saves my order of budgets: {@code ids} must be all my budgets, each once.
	 * @return my budgets in the new order
	 * @throws ApiException 400 {@code validation_error} (missing, extra or repeated ids)
	 */
	@Transactional
	public List<Budget> reorder(UUID userId, List<UUID> ids) {
		List<UUID> mine = this.jdbc.queryForList("SELECT id FROM wallet_budgets WHERE user_id = ? FOR UPDATE",
				UUID.class, userId);
		if (ids == null || ids.size() != mine.size() || new java.util.HashSet<>(ids).size() != ids.size()
				|| !mine.containsAll(ids)) {
			throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "validation_error",
					"ids: must list each of your budgets once");
		}
		for (int i = 0; i < ids.size(); i++) {
			this.jdbc.update("UPDATE wallet_budgets SET position = ? WHERE id = ? AND user_id = ?", i, ids.get(i),
					userId);
		}
		return list(userId);
	}

	/** @param ids all my budgets, in the new order */
	public record OrderRequest(@jakarta.validation.constraints.NotNull List<UUID> ids) {
	}

	/** @throws ApiException 404 {@code budget_not_found} */
	@Transactional
	public void delete(UUID userId, UUID id) {
		mine(userId, id);
		this.jdbc.update("DELETE FROM wallet_budgets WHERE id = ? AND user_id = ?", id, userId);
	}

	/**
	 * Adds the 80% / 100% alerts for this month, once per budget per month. Texts show no
	 * amounts (alerts can be read with the wallet locked), so no currency either; the percent
	 * is from "spent" converted into the budget's currency.
	 */
	@Transactional
	public void checkAlerts(UUID userId) {
		YearMonth month = thisMonth(userId);
		for (Budget budget : list(userId)) {
			String name = (budget.category() != null) ? budget.category().name() : "total";
			if (budget.percent() >= 80) {
				this.alerts.add(userId, AlertService.BUDGET_80, "Budget almost used",
						"You have used 80% of your " + name + " budget this month.",
						"/wallet/budgets", "budget:" + budget.id() + ":" + month + ":80");
			}
			if (budget.percent() >= 100) {
				this.alerts.add(userId, AlertService.BUDGET_100, "Budget used up",
						"You have used all of your " + name + " budget this month.", "/wallet/budgets",
						"budget:" + budget.id() + ":" + month + ":100");
			}
		}
	}

	private Budget one(UUID userId, UUID id) {
		return list(userId).stream().filter((budget) -> budget.id().equals(id)).findFirst().orElseThrow();
	}

	private void mine(UUID userId, UUID id) {
		Boolean found = this.jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM wallet_budgets WHERE id = ? AND user_id = ?)", Boolean.class, id, userId);
		if (!Boolean.TRUE.equals(found)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "budget_not_found", "Budget not found");
		}
	}

	/** No category (total budget), or one of my EXPENSE categories (a budget counts spending only). */
	private void checkCategory(UUID userId, UUID categoryId) {
		if (categoryId == null) {
			return;
		}
		WalletCategories.Category category = this.categories.find(userId, categoryId)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "categoryId: unknown category"));
		if (!"EXPENSE".equals(category.kind())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"categoryId: a budget needs an expense category");
		}
	}

	/**
	 * Expenses per month from {@code from} to {@code to} (all currencies), each month converted into
	 * {@code currencyOf} that month with my rate, not rounded. Only months with an expense are in the
	 * map. Category budgets count the category and its subcategories. Transactions made by goal
	 * movements do not count.
	 */
	private Map<YearMonth, BigDecimal> spentByMonth(UUID userId, Row row, YearMonth from, YearMonth to,
			WalletMoney.Settings settings, java.util.function.Function<YearMonth, String> currencyOf) {
		String categoryFilter = "";
		Object[] args;
		if (row.categoryId() != null) {
			categoryFilter = " AND (category_id = ? OR category_id IN (SELECT id FROM wallet_categories WHERE parent_id = ?))";
			args = new Object[] { userId, Date.valueOf(from.atDay(1)),
					Date.valueOf(to.atEndOfMonth()), row.categoryId(), row.categoryId() };
		}
		else {
			args = new Object[] { userId, Date.valueOf(from.atDay(1)),
					Date.valueOf(to.atEndOfMonth()) };
		}
		Map<YearMonth, BigDecimal> spent = new java.util.HashMap<>();
		this.jdbc.query("SELECT date_trunc('month', date::timestamp)::date AS month, currency, sum(amount) AS total "
				+ "FROM wallet_transactions "
				+ "WHERE user_id = ? AND type = 'EXPENSE' AND date >= ? AND date <= ?" + categoryFilter
				// Goal money is saving, not spending.
				+ " AND NOT EXISTS (SELECT 1 FROM wallet_goal_contributions c WHERE c.transaction_id = wallet_transactions.id)"
				+ " GROUP BY 1, currency", (rs) -> {
					YearMonth month = YearMonth.from(rs.getDate("month").toLocalDate());
					BigDecimal converted = settings.convert(rs.getBigDecimal("total"), rs.getString("currency"),
							currencyOf.apply(month));
					spent.merge(month, converted, BigDecimal::add);
				}, args);
		return spent;
	}

	private List<Row> rows(UUID userId) {
		return this.jdbc.query(
				"SELECT id, amount, currency, category_id FROM wallet_budgets WHERE user_id = ? ORDER BY position, created_at, id",
				(rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
						rs.getString("currency"), rs.getObject("category_id", UUID.class)),
				userId);
	}

	YearMonth thisMonth(UUID userId) {
		return YearMonth.now(this.clock.withZone(this.zones.saved(userId).orElse(ZoneOffset.UTC)));
	}

	private static ApiException exists() {
		return new ApiException(HttpStatus.CONFLICT, "budget_exists",
				"You already have a budget for this category");
	}

	private record Row(UUID id, BigDecimal limit, String currency, UUID categoryId) {
	}

	private record Limit(YearMonth month, BigDecimal amount, String currency) {
	}

	/**
	 * @param limit in {@code currency}
	 * @param currency the budget's currency, USD or MAD
	 * @param category null = total budget
	 * @param spent this month's expenses converted into {@code currency} (my rate)
	 * @param percent whole number, can be over 100
	 * @param status OK (under 80), WARNING (80-99), EXCEEDED (100 or more)
	 */
	public record Budget(UUID id, String limit, String currency, CategoryRef category, String spent, int percent,
			String status) {
	}

	/** Body of POST and PUT /api/wallet/budgets. */
	public record BudgetRequest(
			@jakarta.validation.constraints.NotNull String limit, UUID categoryId,
			@io.swagger.v3.oas.annotations.media.Schema(allowableValues = { "USD", "MAD" }, nullable = true,
					description = "Create: left out = my default wallet currency. Change: left out = keep.")
			@jakarta.validation.constraints.Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String currency) {
	}

	/**
	 * GET /api/wallet/budgets/{id}/history.
	 * @param budget same as one item of the list (this month)
	 * @param months past months, newest first, at most 12
	 * @param summary over the newest up-to-6 months; null when {@code months} is empty
	 */
	public record History(Budget budget, List<BudgetMonth> months, Summary summary) {
	}

	/**
	 * @param month "2026-10"
	 * @param spent that month's expenses in scope, converted into {@code currency} (my current rate)
	 * @param limit the limit that month had, in {@code currency}
	 * @param percent whole number (FLOOR), can be over 100
	 * @param status OK (under 80), WARNING (80-99), EXCEEDED (100 or more)
	 */
	public record BudgetMonth(String month, String spent, String limit, String currency, int percent,
			String status) {
	}

	/**
	 * @param months how many months it covers (1 to 6)
	 * @param average mean spent, 2 decimals
	 * @param underLimit months with spent &lt;= limit
	 * @param biggest the month with the most spent
	 */
	public record Summary(int months, String average, int underLimit, Biggest biggest) {
	}

	public record Biggest(String month, String spent) {
	}

}
