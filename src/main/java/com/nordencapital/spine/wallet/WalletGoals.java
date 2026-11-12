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
 * Saving goals with contributions. Contributions do not change the wallet balance.
 */
@Component
public class WalletGoals {

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public WalletGoals(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** My goals, oldest first, each with its contributions (newest first). */
	@Transactional(readOnly = true)
	public List<Goal> list(UUID userId) {
		List<Object[]> goals = this.jdbc.query(
				"SELECT id, name, target, deadline FROM wallet_goals WHERE user_id = ? ORDER BY created_at, id",
				(rs, row) -> new Object[] { rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getBigDecimal("target"),
						(rs.getDate("deadline") != null) ? rs.getDate("deadline").toLocalDate() : null },
				userId);
		Map<UUID, List<Contribution>> contributions = new HashMap<>();
		Map<UUID, BigDecimal> saved = new HashMap<>();
		this.jdbc.query("SELECT c.id, c.goal_id, c.amount, c.date, c.note FROM wallet_goal_contributions c "
				+ "JOIN wallet_goals g ON g.id = c.goal_id WHERE g.user_id = ? ORDER BY c.date DESC, c.created_at DESC",
				(rs) -> {
					UUID goalId = rs.getObject("goal_id", UUID.class);
					BigDecimal amount = rs.getBigDecimal("amount");
					contributions.computeIfAbsent(goalId, (k) -> new ArrayList<>())
						.add(new Contribution(rs.getObject("id", UUID.class), (amount.signum() < 0) ? TAKE_OUT : ADD,
								Totals.money(amount.abs()), rs.getDate("date").toLocalDate(), rs.getString("note")));
					saved.merge(goalId, amount, BigDecimal::add);
				}, userId);
		return goals.stream().map((g) -> {
			UUID id = (UUID) g[0];
			BigDecimal target = (BigDecimal) g[2];
			BigDecimal total = saved.getOrDefault(id, BigDecimal.ZERO);
			int percent = total.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.FLOOR).intValue();
			return new Goal(id, (String) g[1], Totals.money(target), (LocalDate) g[3],
					Totals.money(total), percent, contributions.getOrDefault(id, List.of()));
		}).toList();
	}

	/** @throws ApiException 400 {@code validation_error} */
	@Transactional
	public Goal create(UUID userId, GoalRequest request) {
		BigDecimal target = Money.parse("target", request.target());
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO wallet_goals (id, user_id, name, target, deadline, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?)", id, userId, request.name(), target,
				date("deadline", request.deadline()), now, now);
		return one(userId, id);
	}

	/** @throws ApiException 404 {@code goal_not_found}, 400 */
	@Transactional
	public Goal update(UUID userId, UUID id, GoalRequest request) {
		mine(userId, id);
		BigDecimal target = Money.parse("target", request.target());
		this.jdbc.update("UPDATE wallet_goals SET name = ?, target = ?, deadline = ?, updated_at = ? "
				+ "WHERE id = ? AND user_id = ?", request.name(), target,
				date("deadline", request.deadline()), Timestamp.from(this.clock.instant()), id, userId);
		return one(userId, id);
	}

	/** Deletes the goal and its contributions. @throws ApiException 404 */
	@Transactional
	public void delete(UUID userId, UUID id) {
		mine(userId, id);
		this.jdbc.update("DELETE FROM wallet_goals WHERE id = ? AND user_id = ?", id, userId);
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

	/** All money now in my goals (it is not in the wallet balance). */
	@Transactional(readOnly = true)
	public BigDecimal savedTotal(UUID userId) {
		BigDecimal total = this.jdbc.queryForObject("SELECT coalesce(sum(c.amount), 0) FROM wallet_goal_contributions c "
				+ "JOIN wallet_goals g ON g.id = c.goal_id WHERE g.user_id = ?", BigDecimal.class, userId);
		return (total != null) ? total : BigDecimal.ZERO;
	}

	private BigDecimal saved(UUID goalId) {
		BigDecimal total = this.jdbc.queryForObject(
				"SELECT coalesce(sum(amount), 0) FROM wallet_goal_contributions WHERE goal_id = ?", BigDecimal.class,
				goalId);
		return (total != null) ? total : BigDecimal.ZERO;
	}

	private static ApiException notEnoughSaved(BigDecimal saved) {
		return new ApiException(HttpStatus.CONFLICT, "not_enough_saved",
				"This goal holds only $" + Totals.money(saved));
	}

	private Goal move(UUID userId, UUID goalId, ContributionRequest request, boolean takeOut) {
		mine(userId, goalId);
		this.jdbc.queryForList("SELECT id FROM wallet_goals WHERE id = ? FOR UPDATE", UUID.class, goalId);
		BigDecimal amount = Money.parse("amount", request.amount());
		if (takeOut) {
			BigDecimal saved = saved(goalId);
			if (amount.compareTo(saved) > 0) {
				throw notEnoughSaved(saved);
			}
			amount = amount.negate();
		}
		Date date = date("date", request.date());
		if (date == null) {
			throw invalid("date: must not be empty");
		}
		this.jdbc.update("INSERT INTO wallet_goal_contributions (id, goal_id, amount, date, note, created_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), goalId, amount, date,
				(request.note() != null && !request.note().isBlank()) ? request.note().strip() : null,
				Timestamp.from(this.clock.instant()));
		return one(userId, goalId);
	}

	/** @return the whole goal, updated. @throws ApiException 404 */
	@Transactional
	public Goal removeContribution(UUID userId, UUID goalId, UUID contributionId) {
		mine(userId, goalId);
		this.jdbc.queryForList("SELECT id FROM wallet_goals WHERE id = ? FOR UPDATE", UUID.class, goalId);
		List<BigDecimal> amount = this.jdbc.queryForList(
				"SELECT amount FROM wallet_goal_contributions WHERE id = ? AND goal_id = ?", BigDecimal.class,
				contributionId, goalId);
		// Removing an "add" must not leave the goal below 0 (money already taken out).
		if (!amount.isEmpty() && saved(goalId).subtract(amount.get(0)).signum() < 0) {
			throw notEnoughSaved(saved(goalId));
		}
		int deleted = this.jdbc.update("DELETE FROM wallet_goal_contributions WHERE id = ? AND goal_id = ?",
				contributionId, goalId);
		if (deleted == 0) {
			throw new ApiException(HttpStatus.NOT_FOUND, "contribution_not_found", "Contribution not found");
		}
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
	 * @param percent whole number, can be over 100
	 * @param contributions newest first
	 */
	public record Goal(UUID id, String name, String target, LocalDate deadline, String saved,
			int percent, List<Contribution> contributions) {
	}

	/**
	 * One movement of a goal.
	 * @param type ADD (money put in, out of the wallet balance) or TAKE_OUT (back to the balance)
	 * @param amount always positive
	 */
	public record Contribution(UUID id, String type, String amount, LocalDate date, String note) {
	}

	static final String ADD = "ADD";

	static final String TAKE_OUT = "TAKE_OUT";

	/** The name is trimmed before validation. */
	public record GoalRequest(
			@NotBlank @Size(max = 60) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@NotNull String target,
			@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-12-31") String deadline) {

		public GoalRequest {
			name = Names.clean(name);
		}

	}

	public record ContributionRequest(@NotNull String amount,
			@NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date,
			@Size(max = 200) String note) {
	}

}
