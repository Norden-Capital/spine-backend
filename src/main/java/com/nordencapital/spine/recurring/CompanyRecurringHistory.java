package com.nordencapital.spine.recurring;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.transaction.HistoryEntry;
import com.nordencapital.spine.transaction.HistoryEntry.Change;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;
import com.nordencapital.spine.user.Avatars;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;

/**
 * The change log of company repeat expenses (table company_recurring_history). Same JSON
 * shape as the transaction history: [{"field": ..., "from": ..., "to": ...}]. Values are
 * display text (the V38 backfill writes the same texts).
 */
@Component
class CompanyRecurringHistory {

	static final String CREATED = "CREATED";

	static final String UPDATED = "UPDATED";

	static final String DELETED = "DELETED";

	static final String RESTORED = "RESTORED";

	static final String PAUSED = "PAUSED";

	static final String RESUMED = "RESUMED";

	static final String PAUSED_UNTIL = "PAUSED_UNTIL";

	static final String SKIPPED = "SKIPPED";

	static final String UNSKIPPED = "UNSKIPPED";

	private static final TypeReference<List<Change>> CHANGES = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final UserRepository users;

	CompanyRecurringHistory(JdbcTemplate jdbc, JsonMapper json, UserRepository users) {
		this.jdbc = jdbc;
		this.json = json;
		this.users = users;
	}

	void record(UUID recurringId, UUID userId, String action, List<Change> changes, Instant at) {
		this.jdbc.update(
				"INSERT INTO company_recurring_history (id, recurring_id, user_id, action, changes, created_at) "
						+ "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)",
				UUID.randomUUID(), recurringId, userId, action, this.json.writeValueAsString(changes),
				Timestamp.from(at));
	}

	/** All entries of a repeat, newest first. */
	List<HistoryEntry> list(UUID recurringId) {
		Map<UUID, User> byId = usersById(this.jdbc.queryForList(
				"SELECT DISTINCT user_id FROM company_recurring_history WHERE recurring_id = ?", UUID.class,
				recurringId));
		return this.jdbc.query(
				"SELECT user_id, action, changes::text AS changes, created_at FROM company_recurring_history "
						+ "WHERE recurring_id = ? ORDER BY created_at DESC, id",
				(rs, rowNum) -> new HistoryEntry(rs.getTimestamp("created_at").toInstant(),
						userRef(byId, rs.getObject("user_id", UUID.class)), rs.getString("action"),
						this.json.readValue(rs.getString("changes"), CHANGES)),
				recurringId);
	}

	Map<UUID, User> usersById(List<UUID> ids) {
		Map<UUID, User> byId = new HashMap<>();
		this.users.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList())
			.forEach((user) -> byId.put(user.getId(), user));
		return byId;
	}

	static UserRef userRef(Map<UUID, User> users, UUID id) {
		if (id == null) {
			return null;
		}
		User user = users.get(id);
		return new UserRef(id, (user != null) ? user.displayName() : null,
				(user != null) ? user.avatarSeed() : Avatars.seed(id, null));
	}

	// ---- snapshots and diff ----

	/** Display values of the fields of a repeat, to compare before and after. */
	record Snapshot(String type, String amount, String description, UUID categoryId, String categoryName,
			String website, String repeat, String startDate, String endDate, String remindDays) {

		static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null, null, null, null);

		static Snapshot of(String type, BigDecimal amount, String description, UUID categoryId, String categoryName,
				String website, String unit, int every, LocalDate start, LocalDate end, List<Integer> remindDays) {
			return new Snapshot(type, Totals.money(amount), description, categoryId, categoryName, website,
					repeatText(unit, every), start.toString(), (end != null) ? end.toString() : null,
					remindText(remindDays));
		}

	}

	/** Only fields that really changed (category compares by id, shows the name). */
	static List<Change> diff(Snapshot before, Snapshot after) {
		List<Change> changes = new ArrayList<>();
		add(changes, "type", before.type(), after.type(), Objects.equals(before.type(), after.type()));
		add(changes, "amount", before.amount(), after.amount(), Objects.equals(before.amount(), after.amount()));
		add(changes, "description", before.description(), after.description(),
				Objects.equals(before.description(), after.description()));
		add(changes, "category", before.categoryName(), after.categoryName(),
				Objects.equals(before.categoryId(), after.categoryId()));
		add(changes, "website", before.website(), after.website(),
				Objects.equals(emptyToNull(before.website()), emptyToNull(after.website())));
		add(changes, "repeat", before.repeat(), after.repeat(), Objects.equals(before.repeat(), after.repeat()));
		add(changes, "startDate", before.startDate(), after.startDate(),
				Objects.equals(before.startDate(), after.startDate()));
		add(changes, "endDate", before.endDate(), after.endDate(), Objects.equals(before.endDate(), after.endDate()));
		add(changes, "remindDays", before.remindDays(), after.remindDays(),
				Objects.equals(before.remindDays(), after.remindDays()));
		return changes;
	}

	/** One date field, e.g. a skipped date: from → to (null = none). */
	static List<Change> date(String field, LocalDate from, LocalDate to) {
		return List.of(new Change(field, (from != null) ? from.toString() : null, (to != null) ? to.toString() : null));
	}

	/** "Monthly", "Every 3 months" (like the app's repeatLabel). */
	static String repeatText(String unit, int every) {
		if (every == 1) {
			return switch (unit) {
				case "DAY" -> "Daily";
				case "WEEK" -> "Weekly";
				case "MONTH" -> "Monthly";
				default -> "Yearly";
			};
		}
		return "Every " + every + " " + unit.toLowerCase(java.util.Locale.ROOT) + "s";
	}

	/** "7, 3 days before", "1 day before", "Due day only". */
	static String remindText(List<Integer> days) {
		if (days == null || days.isEmpty()) {
			return "Due day only";
		}
		String list = days.stream()
			.sorted((a, b) -> Integer.compare(b, a))
			.map(String::valueOf)
			.collect(Collectors.joining(", "));
		return list + ((days.size() == 1 && days.get(0) == 1) ? " day before" : " days before");
	}

	private static void add(List<Change> changes, String field, String from, String to, boolean same) {
		if (!same) {
			changes.add(new Change(field, emptyToNull(from), emptyToNull(to)));
		}
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

}
