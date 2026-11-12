package com.nordencapital.spine.alert;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * In-app alerts, per user (table notifications). Each alert has a dedupe key, so the
 * same alert is never made twice (for example once per budget per month).
 */
@Service
public class AlertService {

	public static final String BUDGET_80 = "BUDGET_80";

	public static final String BUDGET_100 = "BUDGET_100";

	public static final String RECURRING_CREATED = "RECURRING_CREATED";

	public static final String RECURRING_DUE_TOMORROW = "RECURRING_DUE_TOMORROW";

	static final int MAX_LIST = 50;

	private final JdbcTemplate jdbc;

	private final Actors actors;

	private final Clock clock;

	public AlertService(JdbcTemplate jdbc, Actors actors, Clock clock) {
		this.jdbc = jdbc;
		this.actors = actors;
		this.clock = clock;
	}

	/**
	 * Adds an alert for a user, unless one with the same dedupe key exists.
	 * @return true if it was added
	 */
	@Transactional
	public boolean add(UUID userId, String kind, String title, String body, String link, String dedupeKey) {
		return this.jdbc.update("INSERT INTO notifications (id, user_id, kind, title, body, link, dedupe_key, created_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, dedupe_key) DO NOTHING", UUID.randomUUID(), userId,
				kind, title, body, link, dedupeKey, Timestamp.from(this.clock.instant())) > 0;
	}

	/** My newest alerts and how many are unread. */
	@Transactional(readOnly = true)
	public Alerts list(AuthUser principal) {
		User user = this.actors.active(principal);
		Integer unread = this.jdbc.queryForObject(
				"SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, user.getId());
		List<Alert> items = this.jdbc.query(
				"SELECT id, kind, title, body, link, read_at, created_at FROM notifications WHERE user_id = ? "
						+ "ORDER BY created_at DESC, id LIMIT " + MAX_LIST,
				(rs, row) -> new Alert(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
						rs.getString("body"), rs.getString("link"), rs.getTimestamp("read_at") != null,
						rs.getTimestamp("created_at").toInstant()),
				user.getId());
		return new Alerts((unread != null) ? unread : 0, items);
	}

	/** @throws ApiException 404 {@code alert_not_found} (also for other users' alerts) */
	@Transactional
	public void markRead(AuthUser principal, UUID id) {
		User user = this.actors.active(principal);
		Boolean mine = this.jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM notifications WHERE id = ? AND user_id = ?)", Boolean.class, id,
				user.getId());
		if (!Boolean.TRUE.equals(mine)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "alert_not_found", "Alert not found");
		}
		this.jdbc.update("UPDATE notifications SET read_at = coalesce(read_at, ?) WHERE id = ?",
				Timestamp.from(this.clock.instant()), id);
	}

	@Transactional
	public void markAllRead(AuthUser principal) {
		User user = this.actors.active(principal);
		this.jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
				Timestamp.from(this.clock.instant()), user.getId());
	}

	public record Alerts(int unreadCount, List<Alert> items) {
	}

	/**
	 * @param link a frontend path hint (may be null)
	 */
	public record Alert(UUID id, String kind, String title, String body, String link, boolean read,
			Instant createdAt) {
	}

}
