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
 * per month.
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
			BigDecimal spent = spent(userId, row, month, settings);
			int percent = spent.multiply(BigDecimal.valueOf(100)).divide(row.limit(), 0, RoundingMode.FLOOR).intValue();
			String status = (percent >= 100) ? "EXCEEDED" : (percent >= 80) ? "WARNING" : "OK";
			WalletCategories.Category category = (row.categoryId() != null) ? categoriesById.get(row.categoryId())
					: null;
			CategoryRef ref = (category != null) ? new CategoryRef(category.id(), category.name(), category.icon(),
					category.archived(), category.parentId(), category.kind()) : null;
			return new Budget(row.id(), Totals.money(row.limit()), row.currency(), ref, Totals.money(spent), percent,
					status);
		}).toList();
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
	 * This month's expenses (all currencies) in the budget's currency, not rounded. Transactions
	 * made by goal movements do not count.
	 */
	private BigDecimal spent(UUID userId, Row row, YearMonth month, WalletMoney.Settings settings) {
		String categoryFilter = "";
		Object[] args;
		if (row.categoryId() != null) {
			categoryFilter = " AND (category_id = ? OR category_id IN (SELECT id FROM wallet_categories WHERE parent_id = ?))";
			args = new Object[] { userId, Date.valueOf(month.atDay(1)),
					Date.valueOf(month.atEndOfMonth()), row.categoryId(), row.categoryId() };
		}
		else {
			args = new Object[] { userId, Date.valueOf(month.atDay(1)),
					Date.valueOf(month.atEndOfMonth()) };
		}
		BigDecimal[] spent = { BigDecimal.ZERO };
		this.jdbc.query("SELECT currency, sum(amount) AS total FROM wallet_transactions "
				+ "WHERE user_id = ? AND type = 'EXPENSE' AND date >= ? AND date <= ?" + categoryFilter
				// Goal money is saving, not spending.
				+ " AND NOT EXISTS (SELECT 1 FROM wallet_goal_contributions c WHERE c.transaction_id = wallet_transactions.id)"
				+ " GROUP BY currency", (rs) -> {
					spent[0] = spent[0]
						.add(settings.convert(rs.getBigDecimal("total"), rs.getString("currency"), row.currency()));
				}, args);
		return spent[0];
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

}
