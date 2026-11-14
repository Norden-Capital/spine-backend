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
import com.nordencapital.spine.push.PushService;
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

	/** Scope: a company alert, sent to everyone in the company (all active users). */
	public static final String COMPANY = "COMPANY";

	/** Scope: a private alert (one person's wallet), only for that person. */
	public static final String PRIVATE = "PRIVATE";

	static final int MAX_LIST = 50;

	private final JdbcTemplate jdbc;

	private final Actors actors;

	private final Clock clock;

	private final PushService push;

	public AlertService(JdbcTemplate jdbc, Actors actors, Clock clock, PushService push) {
		this.jdbc = jdbc;
		this.actors = actors;
		this.clock = clock;
		this.push = push;
	}

	/**
	 * Adds a PRIVATE alert for one person (their wallet), unless one with the same dedupe
	 * key exists.
	 * @return true if it was added
	 */
	@Transactional
	public boolean add(UUID userId, String kind, String title, String body, String link, String dedupeKey) {
		return add(userId, PRIVATE, kind, title, body, link, dedupeKey);
	}

	/**
	 * Adds an alert for a user, unless one with the same dedupe key exists. Only ACTIVE
	 * users get alerts (an invited or disabled person gets none). A new alert is also sent to
	 * the person's phones (Web Push), after the commit.
	 * @param scope {@link #COMPANY} or {@link #PRIVATE}
	 * @return true if it was added
	 */
	@Transactional
	public boolean add(UUID userId, String scope, String kind, String title, String body, String link,
			String dedupeKey) {
		UUID id = UUID.randomUUID();
		boolean added = this.jdbc.update("INSERT INTO notifications (id, user_id, scope, kind, title, body, link, "
				+ "dedupe_key, created_at) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM users WHERE id = ? "
				+ "AND status = 'ACTIVE') ON CONFLICT (user_id, dedupe_key) DO NOTHING",
				id, userId, scope, kind, title, body, link, dedupeKey,
				Timestamp.from(this.clock.instant()), userId) > 0;
		if (added) {
			this.push.alertAdded(userId, id, title, body, link);
		}
		return added;
	}

	/** Everyone in the company: all active users (owner and members). */
	public List<UUID> companyUsers() {
		return this.jdbc.queryForList("SELECT id FROM users WHERE status = 'ACTIVE'", UUID.class);
	}

	/**
	 * Adds a COMPANY alert for everyone in the company, once per person (same dedupe key).
	 * @return how many were added
	 */
	@Transactional
	public int addForCompany(String kind, String title, String body, String link, String dedupeKey) {
		int added = 0;
		for (UUID userId : companyUsers()) {
			added += add(userId, COMPANY, kind, title, body, link, dedupeKey) ? 1 : 0;
		}
		return added;
	}

	/** My newest alerts and how many are unread. */
	@Transactional(readOnly = true)
	public Alerts list(AuthUser principal) {
		User user = this.actors.active(principal);
		Integer unread = this.jdbc.queryForObject(
				"SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, user.getId());
		List<Alert> items = this.jdbc.query(
				"SELECT id, scope, kind, title, body, link, read_at, created_at FROM notifications WHERE user_id = ? "
						+ "ORDER BY created_at DESC, id LIMIT " + MAX_LIST,
				(rs, row) -> new Alert(rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("kind"), rs.getString("title"),
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
	 * @param scope COMPANY (sent to everyone in the company) or PRIVATE (only mine, from my wallet)
	 * @param link a frontend path hint (may be null)
	 */
	public record Alert(UUID id, String scope, String kind, String title, String body, String link, boolean read,
			Instant createdAt) {
	}

}
