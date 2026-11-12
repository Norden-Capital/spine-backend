package com.nordencapital.spine.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Login history: one row per login attempt of a known user (table login_events).
 */
@Component
public class LoginEvents {

	/** IPv6 text is at most 45 characters. */
	private static final int MAX_IP_LENGTH = 45;

	private final JdbcTemplate jdbc;

	public LoginEvents(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void record(UUID userId, boolean success, String ip, Instant at) {
		String safeIp = (ip != null && ip.length() > MAX_IP_LENGTH) ? ip.substring(0, MAX_IP_LENGTH) : ip;
		this.jdbc.update("INSERT INTO login_events (id, user_id, created_at, success, ip) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), userId, Timestamp.from(at), success, safeIp);
	}

	/**
	 * @return the user's attempts at or after {@code from}, oldest first
	 */
	public List<Attempt> since(UUID userId, Instant from) {
		return this.jdbc.query(
				"SELECT created_at, success FROM login_events WHERE user_id = ? AND created_at >= ? ORDER BY created_at",
				(rs, rowNum) -> new Attempt(rs.getTimestamp("created_at").toInstant(), rs.getBoolean("success")),
				userId, Timestamp.from(from));
	}

	/**
	 * The last successful login before {@code before} (not included), or null.
	 * @param before null = before the newest successful login
	 */
	public Instant previousSuccess(UUID userId, Instant before) {
		List<Instant> found = (before != null)
				? this.jdbc.query("SELECT max(created_at) AS at FROM login_events WHERE user_id = ? AND success "
						+ "AND created_at < ?", (rs, rowNum) -> toInstant(rs.getTimestamp("at")), userId,
						Timestamp.from(before.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
				: this.jdbc.query("SELECT created_at AS at FROM login_events WHERE user_id = ? AND success "
						+ "ORDER BY created_at DESC OFFSET 1 LIMIT 1", (rs, rowNum) -> toInstant(rs.getTimestamp("at")),
						userId);
		return found.isEmpty() ? null : found.get(0);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return (timestamp != null) ? timestamp.toInstant() : null;
	}

	public record Attempt(Instant at, boolean success) {
	}

}
