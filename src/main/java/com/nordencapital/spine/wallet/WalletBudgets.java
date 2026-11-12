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
 * Monthly budgets: a limit per currency, either for everything (no category) or for one
 * category and its subcategories. Alerts at 80% and 100%, once per budget per month.
 */
@Component
public class WalletBudgets {

	private final JdbcTemplate jdbc;

	private final WalletCategories categories;

	private final AlertService alerts;

	private final UserZones zones;

	private final Clock clock;

	public WalletBudgets(JdbcTemplate jdbc, WalletCategories categories, AlertService alerts, UserZones zones,
			Clock clock) {
		this.jdbc = jdbc;
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
		Map<UUID, WalletCategories.Category> categoriesById = this.categories.byIds(userId,
				rows.stream().map(Row::categoryId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
		return rows.stream().map((row) -> {
			BigDecimal spent = spent(userId, row, month);
			int percent = spent.multiply(BigDecimal.valueOf(100)).divide(row.limit(), 0, RoundingMode.FLOOR).intValue();
			String status = (percent >= 100) ? "EXCEEDED" : (percent >= 80) ? "WARNING" : "OK";
			WalletCategories.Category category = (row.categoryId() != null) ? categoriesById.get(row.categoryId())
					: null;
			CategoryRef ref = (category != null) ? new CategoryRef(category.id(), category.name(), category.icon(),
					category.archived(), category.parentId()) : null;
			return new Budget(row.id(), row.currency(), Totals.money(row.limit()), ref, Totals.money(spent), percent,
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
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		try {
			this.jdbc.update("INSERT INTO wallet_budgets (id, user_id, currency, amount, category_id, created_at, "
					+ "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, userId, request.currency(), limit,
					request.categoryId(), now, now);
		}
		catch (DuplicateKeyException ex) {
			throw exists();
		}
		checkAlerts(userId);
		return one(userId, id);
	}

	/** @throws ApiException 404 {@code budget_not_found}, 409 {@code budget_exists}, 400 */
	@Transactional
	public Budget update(UUID userId, UUID id, BudgetRequest request) {
		mine(userId, id);
		BigDecimal limit = Money.parse("limit", request.limit());
		checkCategory(userId, request.categoryId());
		try {
			this.jdbc.update("UPDATE wallet_budgets SET currency = ?, amount = ?, category_id = ?, updated_at = ? "
					+ "WHERE id = ? AND user_id = ?", request.currency(), limit, request.categoryId(),
					Timestamp.from(this.clock.instant()), id, userId);
		}
		catch (DuplicateKeyException ex) {
			throw exists();
		}
		checkAlerts(userId);
		return one(userId, id);
	}

	/** @throws ApiException 404 {@code budget_not_found} */
	@Transactional
	public void delete(UUID userId, UUID id) {
		mine(userId, id);
		this.jdbc.update("DELETE FROM wallet_budgets WHERE id = ? AND user_id = ?", id, userId);
	}

	/**
	 * Adds the 80% / 100% alerts for this month, once per budget per month. Texts show no
	 * amounts.
	 */
	@Transactional
	public void checkAlerts(UUID userId) {
		YearMonth month = thisMonth(userId);
		for (Budget budget : list(userId)) {
			String name = (budget.category() != null) ? budget.category().name() + " (" + budget.currency() + ")"
					: "total " + budget.currency();
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

	private void checkCategory(UUID userId, UUID categoryId) {
		if (categoryId != null && this.categories.find(userId, categoryId).isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "categoryId: unknown category");
		}
	}

	private BigDecimal spent(UUID userId, Row row, YearMonth month) {
		String categoryFilter = "";
		Object[] args;
		if (row.categoryId() != null) {
			categoryFilter = " AND (category_id = ? OR category_id IN (SELECT id FROM wallet_categories WHERE parent_id = ?))";
			args = new Object[] { userId, row.currency(), Date.valueOf(month.atDay(1)),
					Date.valueOf(month.atEndOfMonth()), row.categoryId(), row.categoryId() };
		}
		else {
			args = new Object[] { userId, row.currency(), Date.valueOf(month.atDay(1)),
					Date.valueOf(month.atEndOfMonth()) };
		}
		BigDecimal spent = this.jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM wallet_transactions "
				+ "WHERE user_id = ? AND type = 'EXPENSE' AND currency = ? AND date >= ? AND date <= ?" + categoryFilter,
				BigDecimal.class, args);
		return (spent != null) ? spent : BigDecimal.ZERO;
	}

	private List<Row> rows(UUID userId) {
		return this.jdbc.query(
				"SELECT id, currency, amount, category_id FROM wallet_budgets WHERE user_id = ? ORDER BY currency, created_at",
				(rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("currency"), rs.getBigDecimal("amount"),
						rs.getObject("category_id", UUID.class)),
				userId);
	}

	YearMonth thisMonth(UUID userId) {
		return YearMonth.now(this.clock.withZone(this.zones.saved(userId).orElse(ZoneOffset.UTC)));
	}

	private static ApiException exists() {
		return new ApiException(HttpStatus.CONFLICT, "budget_exists",
				"You already have a budget for this currency and category");
	}

	private record Row(UUID id, String currency, BigDecimal limit, UUID categoryId) {
	}

	/**
	 * @param category null = total budget for the currency
	 * @param percent whole number, can be over 100
	 * @param status OK (under 80), WARNING (80-99), EXCEEDED (100 or more)
	 */
	public record Budget(UUID id, String currency, String limit, CategoryRef category, String spent, int percent,
			String status) {
	}

	/** Body of POST and PUT /api/wallet/budgets. */
	public record BudgetRequest(
			@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "USD|MAD",
					message = "must be USD or MAD") String currency,
			@jakarta.validation.constraints.NotNull String limit, UUID categoryId) {
	}

}
