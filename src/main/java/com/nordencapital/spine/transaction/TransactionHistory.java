package com.nordencapital.spine.transaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.transaction.HistoryEntry.Change;
import com.nordencapital.spine.user.User;

/**
 * The change log of transactions (table transaction_history). Changes are stored as
 * JSON: [{"field": ..., "from": ..., "to": ...}].
 */
@Component
class TransactionHistory {

	static final String CREATED = "CREATED";

	static final String UPDATED = "UPDATED";

	static final String DELETED = "DELETED";

	static final String RESTORED = "RESTORED";

	private static final TypeReference<List<Change>> CHANGES = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	TransactionHistory(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	void record(UUID transactionId, UUID userId, String action, List<Change> changes, Instant at) {
		this.jdbc.update(
				"INSERT INTO transaction_history (id, transaction_id, user_id, action, changes, created_at) "
						+ "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)",
				UUID.randomUUID(), transactionId, userId, action, this.json.writeValueAsString(changes),
				Timestamp.from(at));
	}

	/** All entries of a transaction, newest first. */
	List<HistoryEntry> list(UUID transactionId, Map<UUID, User> users) {
		return this.jdbc.query(
				"SELECT user_id, action, changes::text AS changes, created_at FROM transaction_history "
						+ "WHERE transaction_id = ? ORDER BY created_at DESC, id",
				(rs, rowNum) -> new HistoryEntry(rs.getTimestamp("created_at").toInstant(),
						TransactionViews.userRef(users, rs.getObject("user_id", UUID.class)), rs.getString("action"),
						this.json.readValue(rs.getString("changes"), CHANGES)),
				transactionId);
	}

	List<UUID> userIds(UUID transactionId) {
		return this.jdbc.queryForList("SELECT DISTINCT user_id FROM transaction_history WHERE transaction_id = ?",
				UUID.class, transactionId);
	}

}
