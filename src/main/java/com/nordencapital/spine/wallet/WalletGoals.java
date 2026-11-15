package com.nordencapital.spine.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.Totals;

/**
 * Saving goals with contributions. Each goal has its own currency (USD or MAD); its target,
 * contributions, saved and percent are in that currency.
 * <p>
 * Every movement has its own wallet transaction (same amount, goal currency, movement date,
 * description "Goal: name"): an add is an EXPENSE in my "Goals" category, a take out an INCOME in
 * my "From goals" category. So the wallet balance (income - expense) already holds goal money.
 * Removing a movement, or the goal, removes its transactions too. Budgets ignore these
 * transactions (saving is not spending).
 */
@Component
public class WalletGoals {

	private final JdbcTemplate jdbc;

	private final WalletMoney money;

	private final WalletCategories categories;

	private final Clock clock;

	public WalletGoals(JdbcTemplate jdbc, WalletMoney money, WalletCategories categories, Clock clock) {
		this.jdbc = jdbc;
		this.money = money;
		this.categories = categories;
		this.clock = clock;
	}

	/** My goals in my order (drag and drop), then oldest first, each with its contributions (newest first). */
	@Transactional(readOnly = true)
	public List<Goal> list(UUID userId) {
		List<Object[]> goals = this.jdbc.query(
				"SELECT id, name, target, deadline, currency FROM wallet_goals WHERE user_id = ? "
						+ "ORDER BY position, created_at, id",
				(rs, row) -> new Object[] { rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getBigDecimal("target"),
						(rs.getDate("deadline") != null) ? rs.getDate("deadline").toLocalDate() : null,
						rs.getString("currency") },
				userId);
		Map<UUID, List<Contribution>> contributions = new HashMap<>();
		Map<UUID, BigDecimal> saved = new HashMap<>();
		this.jdbc.query("SELECT c.id, c.goal_id, c.amount, c.date, c.note, c.transaction_id FROM wallet_goal_contributions c "
				+ "JOIN wallet_goals g ON g.id = c.goal_id WHERE g.user_id = ? ORDER BY c.date DESC, c.created_at DESC",
				(rs) -> {
					UUID goalId = rs.getObject("goal_id", UUID.class);
					BigDecimal amount = rs.getBigDecimal("amount");
					contributions.computeIfAbsent(goalId, (k) -> new ArrayList<>())
						.add(new Contribution(rs.getObject("id", UUID.class), (amount.signum() < 0) ? TAKE_OUT : ADD,
								Totals.money(amount.abs()), rs.getDate("date").toLocalDate(), rs.getString("note"),
								rs.getObject("transaction_id", UUID.class)));
					saved.merge(goalId, amount, BigDecimal::add);
				}, userId);
		return goals.stream().map((g) -> {
			UUID id = (UUID) g[0];
			BigDecimal target = (BigDecimal) g[2];
			BigDecimal total = saved.getOrDefault(id, BigDecimal.ZERO);
			int percent = total.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.FLOOR).intValue();
			return new Goal(id, (String) g[1], Totals.money(target), (String) g[4], (LocalDate) g[3],
					Totals.money(total), percent, contributions.getOrDefault(id, List.of()));
		}).toList();
	}

	/** @throws ApiException 400 {@code validation_error} */
	@Transactional
	public Goal create(UUID userId, GoalRequest request) {
		BigDecimal target = Money.parse("target", request.target());
		String currency = WalletMoney.pick(request.currency(), this.money.settings(userId).currency());
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		// A new goal goes last.
		this.jdbc.update("INSERT INTO wallet_goals (id, user_id, name, target, currency, deadline, position, "
				+ "created_at, updated_at) SELECT ?, ?, ?, ?, ?, ?, coalesce(max(position) + 1, 0), ?, ? FROM wallet_goals "
				+ "WHERE user_id = ?", id, userId, request.name(), target, currency, date("deadline", request.deadline()),
				now, now, userId);
		return one(userId, id);
	}

	/**
	 * Currency left out = keep. A new currency also applies to the money already in the goal
	 * (amounts are not converted), and to its transactions. A new name also renames their
	 * description ("Goal: name").
	 * @throws ApiException 404 {@code goal_not_found}, 400
	 */
	@Transactional
	public Goal update(UUID userId, UUID id, GoalRequest request) {
		mine(userId, id);
		BigDecimal target = Money.parse("target", request.target());
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("UPDATE wallet_goals SET name = ?, target = ?, currency = coalesce(?, currency), deadline = ?, "
				+ "updated_at = ? WHERE id = ? AND user_id = ?", request.name(), target, request.currency(),
				date("deadline", request.deadline()), now, id, userId);
		this.jdbc.update("UPDATE wallet_transactions t SET currency = g.currency, description = left('Goal: ' || g.name, 200), "
				+ "updated_at = ? FROM wallet_goal_contributions c, wallet_goals g WHERE c.transaction_id = t.id "
				+ "AND g.id = c.goal_id AND g.id = ? AND t.user_id = ? "
				+ "AND (t.currency <> g.currency OR t.description <> left('Goal: ' || g.name, 200))", now, id, userId);
		return one(userId, id);
	}

	/**
	 * Saves my order of goals: {@code ids} must be all my goals, each once.
	 * @return my goals in the new order
	 * @throws ApiException 400 {@code validation_error} (missing, extra or repeated ids)
	 */
	@Transactional
	public List<Goal> reorder(UUID userId, List<UUID> ids) {
		List<UUID> mine = this.jdbc.queryForList("SELECT id FROM wallet_goals WHERE user_id = ? FOR UPDATE", UUID.class,
				userId);
		if (ids == null || ids.size() != mine.size() || new java.util.HashSet<>(ids).size() != ids.size()
				|| !mine.containsAll(ids)) {
			throw invalid("ids: must list each of your goals once");
		}
		for (int i = 0; i < ids.size(); i++) {
			this.jdbc.update("UPDATE wallet_goals SET position = ? WHERE id = ? AND user_id = ?", i, ids.get(i), userId);
		}
		return list(userId);
	}

	/**
	 * Deletes the goal, its contributions and their transactions (the money is back in the
	 * balance). @throws ApiException 404
	 */
	@Transactional
	public void delete(UUID userId, UUID id) {
		mine(userId, id);
		List<UUID> transactions = this.jdbc.queryForList(
				"SELECT transaction_id FROM wallet_goal_contributions WHERE goal_id = ? AND transaction_id IS NOT NULL",
				UUID.class, id);
		this.jdbc.update("DELETE FROM wallet_goals WHERE id = ? AND user_id = ?", id, userId);
		deleteTransactions(userId, transactions);
	}

	/** @return the whole goal, updated. @throws ApiException 404, 400 */
	@Transactional
	public Goal contribute(UUID userId, UUID goalId, ContributionRequest request) {
		return move(userId, goalId, request, false);
	}

	/**
	 * Takes money back out of a goal: it goes back to the wallet balance.
	 * @throws ApiException 404 {@code goal_not_found}, 400 {@code validation_error}, 409
	 * {@code not_enough_saved} (more than the goal holds)
	 */
	@Transactional
	public Goal takeOut(UUID userId, UUID goalId, ContributionRequest request) {
		return move(userId, goalId, request, true);
	}

	/**
	 * All money now in my goals (information only: the balance already left it out, through
	 * the goal transactions), each goal converted into the default currency of {@code settings}
	 * (not rounded).
	 */
	@Transactional(readOnly = true)
	public BigDecimal savedTotal(UUID userId, WalletMoney.Settings settings) {
		BigDecimal[] total = { BigDecimal.ZERO };
		this.jdbc.query("SELECT g.currency, sum(c.amount) AS total FROM wallet_goal_contributions c "
				+ "JOIN wallet_goals g ON g.id = c.goal_id WHERE g.user_id = ? GROUP BY g.currency", (rs) -> {
					total[0] = total[0].add(settings.toDefault(rs.getBigDecimal("total"), rs.getString("currency")));
				}, userId);
		return total[0];
	}

	private BigDecimal saved(UUID goalId) {
		BigDecimal total = this.jdbc.queryForObject(
				"SELECT coalesce(sum(amount), 0) FROM wallet_goal_contributions WHERE goal_id = ?", BigDecimal.class,
				goalId);
		return (total != null) ? total : BigDecimal.ZERO;
	}

	private ApiException notEnoughSaved(UUID goalId, BigDecimal saved) {
		String currency = this.jdbc.queryForObject("SELECT currency FROM wallet_goals WHERE id = ?", String.class, goalId);
		return new ApiException(HttpStatus.CONFLICT, "not_enough_saved",
				"This goal holds only " + WalletMoney.format(saved, currency));
	}

	private Goal move(UUID userId, UUID goalId, ContributionRequest request, boolean takeOut) {
		mine(userId, goalId);
		this.jdbc.queryForList("SELECT id FROM wallet_goals WHERE id = ? FOR UPDATE", UUID.class, goalId);
		BigDecimal amount = Money.parse("amount", request.amount());
		if (takeOut) {
			BigDecimal saved = saved(goalId);
			if (amount.compareTo(saved) > 0) {
				throw notEnoughSaved(goalId, saved);
			}
			amount = amount.negate();
		}
		Date date = date("date", request.date());
		if (date == null) {
			throw invalid("date: must not be empty");
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		String type = takeOut ? "INCOME" : "EXPENSE";
		UUID categoryId = this.categories.goalCategory(userId, type);
		UUID transactionId = UUID.randomUUID();
		this.jdbc.update("INSERT INTO wallet_transactions (id, user_id, type, amount, currency, date, description, "
				+ "category_id, created_at) SELECT ?, ?, ?, ?, currency, ?, left('Goal: ' || name, 200), ?, ? "
				+ "FROM wallet_goals WHERE id = ?", transactionId, userId, type, amount.abs(), date, categoryId, now,
				goalId);
		this.jdbc.update("INSERT INTO wallet_goal_contributions (id, goal_id, amount, date, note, transaction_id, "
				+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), goalId, amount, date,
				(request.note() != null && !request.note().isBlank()) ? request.note().strip() : null, transactionId,
				now);
		return one(userId, goalId);
	}

	/** Goal transactions are plain rows (never files): just delete them, and alerts that link to them. */
	private void deleteTransactions(UUID userId, List<UUID> ids) {
		for (UUID id : ids) {
			this.jdbc.update("DELETE FROM wallet_transactions WHERE id = ? AND user_id = ?", id, userId);
			this.jdbc.update("DELETE FROM notifications WHERE user_id = ? AND link = ?", userId,
					"/wallet/transactions/" + id);
		}
	}

	/**
	 * The goal movement made with this transaction, if any.
	 * @return [goalId, contributionId], or empty for a normal transaction
	 */
	@Transactional(readOnly = true)
	public java.util.Optional<UUID[]> movementOf(UUID transactionId) {
		return this.jdbc
			.query("SELECT goal_id, id FROM wallet_goal_contributions WHERE transaction_id = ?",
					(rs, row) -> new UUID[] { rs.getObject("goal_id", UUID.class), rs.getObject("id", UUID.class) },
					transactionId)
			.stream()
			.findFirst();
	}

	/**
	 * Undo of one movement: removes it and its transaction.
	 * @return the whole goal, updated. @throws ApiException 404, 409 {@code not_enough_saved}
	 */
	@Transactional
	public Goal removeContribution(UUID userId, UUID goalId, UUID contributionId) {
		mine(userId, goalId);
		this.jdbc.queryForList("SELECT id FROM wallet_goals WHERE id = ? FOR UPDATE", UUID.class, goalId);
		List<BigDecimal> amount = this.jdbc.queryForList(
				"SELECT amount FROM wallet_goal_contributions WHERE id = ? AND goal_id = ?", BigDecimal.class,
				contributionId, goalId);
		// Removing an "add" must not leave the goal below 0 (money already taken out).
		if (!amount.isEmpty() && saved(goalId).subtract(amount.get(0)).signum() < 0) {
			throw notEnoughSaved(goalId, saved(goalId));
		}
		List<UUID> transaction = this.jdbc.queryForList(
				"SELECT transaction_id FROM wallet_goal_contributions WHERE id = ? AND goal_id = ? AND transaction_id IS NOT NULL",
				UUID.class, contributionId, goalId);
		int deleted = this.jdbc.update("DELETE FROM wallet_goal_contributions WHERE id = ? AND goal_id = ?",
				contributionId, goalId);
		if (deleted == 0) {
			throw new ApiException(HttpStatus.NOT_FOUND, "contribution_not_found", "Contribution not found");
		}
		deleteTransactions(userId, transaction);
		return one(userId, goalId);
	}

	private Goal one(UUID userId, UUID id) {
		return list(userId).stream().filter((goal) -> goal.id().equals(id)).findFirst().orElseThrow();
	}

	private void mine(UUID userId, UUID id) {
		Boolean found = this.jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM wallet_goals WHERE id = ? AND user_id = ?)", Boolean.class, id, userId);
		if (!Boolean.TRUE.equals(found)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "goal_not_found", "Goal not found");
		}
	}

	private static Date date(String field, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Date.valueOf(LocalDate.parse(text));
		}
		catch (DateTimeParseException ex) {
			throw invalid(field + ": must be a real date like 2026-03-31");
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	/**
	 * @param target in {@code currency}
	 * @param currency the goal's currency, USD or MAD (its contributions are in it too)
	 * @param saved in {@code currency}
	 * @param percent whole number, can be over 100
	 * @param contributions newest first
	 */
	public record Goal(UUID id, String name, String target, String currency, LocalDate deadline, String saved,
			int percent, List<Contribution> contributions) {
	}

	/**
	 * One movement of a goal.
	 * @param type ADD (money put in, out of the wallet balance) or TAKE_OUT (back to the balance)
	 * @param amount always positive, in the goal's currency
	 * @param transactionId its wallet transaction (ADD = EXPENSE in "Goals", TAKE_OUT = INCOME in
	 * "From goals")
	 */
	public record Contribution(UUID id, String type, String amount, LocalDate date, String note,
			@io.swagger.v3.oas.annotations.media.Schema(description = "Its wallet transaction: ADD = EXPENSE in my "
					+ "\"Goals\" category, TAKE_OUT = INCOME in \"From goals\"") UUID transactionId) {
	}

	static final String ADD = "ADD";

	static final String TAKE_OUT = "TAKE_OUT";

	/** The name is trimmed before validation. */
	public record GoalRequest(
			@NotBlank @Size(max = 60) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@NotNull String target,
			@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-12-31") String deadline,
			@io.swagger.v3.oas.annotations.media.Schema(allowableValues = { "USD", "MAD" }, nullable = true,
					description = "Create: left out = my default wallet currency. Change: left out = keep.")
			@Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String currency) {

		public GoalRequest {
			name = Names.clean(name);
		}

	}

	/** @param ids all my goals, in the new order */
	public record OrderRequest(@NotNull List<UUID> ids) {
	}

	/** @param amount in the goal's currency */
	public record ContributionRequest(@NotNull String amount,
			@NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
			@Size(max = 200) String note) {
	}

}
