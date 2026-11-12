package com.nordencapital.spine.wallet;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.category.CategoryService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Like;
import com.nordencapital.spine.common.Names;

/**
 * My private wallet tags. Only the owner ever sees them. Nothing is deleted, only
 * archived.
 */
@Component
public class WalletTags {

	static final int MAX_RESULTS = 50;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public WalletTags(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Picker: my active tags containing the text, most used in 90 days, then by name. */
	@Transactional(readOnly = true)
	public List<Pick> search(UUID userId, String text) {
		String cleaned = (text != null) ? text.strip() : "";
		if (cleaned.length() > Names.MAX_LENGTH) {
			return List.of();
		}
		return query("WHERE g.user_id = ? AND NOT g.archived AND lower(g.name) LIKE ? ESCAPE '\\' "
				+ "GROUP BY g.id, g.name, g.archived ORDER BY count(t.id) DESC, lower(g.name), g.id LIMIT " + MAX_RESULTS,
				userId, Like.contains(cleaned));
	}

	/** All my tags, archived too, by name. */
	@Transactional(readOnly = true)
	public List<Pick> all(UUID userId) {
		return query("WHERE g.user_id = ? GROUP BY g.id, g.name, g.archived ORDER BY lower(g.name), g.id", userId);
	}

	/**
	 * My active tag with this name, or a new one.
	 * @return the tag and whether it is new
	 * @throws ApiException 409 {@code tag_archived}
	 */
	@Transactional
	public Created create(UUID userId, String name) {
		Optional<Tag> existing = this.jdbc
			.query("SELECT id, name, archived FROM wallet_tags WHERE user_id = ? AND lower(name) = lower(?)",
					(rs, row) -> new Tag(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("archived")),
					userId, name)
			.stream()
			.findFirst();
		if (existing.isPresent()) {
			if (existing.get().archived()) {
				throw new ApiException(HttpStatus.CONFLICT, "tag_archived", "This tag exists but is archived");
			}
			return new Created(existing.get().pick(0), false);
		}
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		try {
			this.jdbc.update("INSERT INTO wallet_tags (id, user_id, name, archived, created_at, updated_at) "
					+ "VALUES (?, ?, ?, false, ?, ?)", id, userId, name, now, now);
		}
		catch (DuplicateKeyException ex) {
			throw nameTaken();
		}
		return new Created(new Pick(id, name, false, 0), true);
	}

	/** @throws ApiException 404 {@code tag_not_found}, 409 {@code name_taken} */
	@Transactional
	public Pick rename(UUID userId, UUID id, String name) {
		mine(userId, id);
		try {
			this.jdbc.update("UPDATE wallet_tags SET name = ?, updated_at = ? WHERE id = ?", name,
					Timestamp.from(this.clock.instant()), id);
		}
		catch (DuplicateKeyException ex) {
			throw nameTaken();
		}
		return one(userId, id);
	}

	/** @throws ApiException 404 {@code tag_not_found} */
	@Transactional
	public Pick setArchived(UUID userId, UUID id, boolean archived) {
		mine(userId, id);
		this.jdbc.update("UPDATE wallet_tags SET archived = ?, updated_at = ? WHERE id = ?", archived,
				Timestamp.from(this.clock.instant()), id);
		return one(userId, id);
	}

	/** My tags with these ids (unknown or other people's ids are left out). */
	List<Tag> mine(UUID userId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return this.jdbc.query("SELECT id, name, archived FROM wallet_tags WHERE user_id = ? AND id = ANY (?)",
				(rs, row) -> new Tag(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("archived")),
				userId, ids.toArray(new UUID[0]));
	}

	private Tag mine(UUID userId, UUID id) {
		return mine(userId, List.of(id)).stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tag_not_found", "Tag not found"));
	}

	private Pick one(UUID userId, UUID id) {
		return query("WHERE g.user_id = ? AND g.id = ? GROUP BY g.id, g.name, g.archived", userId, id).get(0);
	}

	private List<Pick> query(String tail, Object... args) {
		LocalDate since = LocalDate.now(this.clock.withZone(ZoneOffset.UTC)).minusDays(CategoryService.USAGE_DAYS);
		Object[] all = new Object[args.length + 1];
		all[0] = Date.valueOf(since);
		System.arraycopy(args, 0, all, 1, args.length);
		return this.jdbc.query("SELECT g.id, g.name, g.archived, count(t.id) AS n FROM wallet_tags g "
				+ "LEFT JOIN wallet_transaction_tags tt ON tt.tag_id = g.id "
				+ "LEFT JOIN wallet_transactions t ON t.id = tt.transaction_id AND t.date >= ? " + tail,
				(rs, row) -> new Pick(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("archived"),
						rs.getLong("n")),
				all);
	}

	private static ApiException nameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "name_taken", "You already have a tag with this name");
	}

	record Tag(UUID id, String name, boolean archived) {

		Pick pick(long usageCount) {
			return new Pick(this.id, this.name, this.archived, usageCount);
		}

	}

	/** A wallet tag as the API returns it. */
	public record Pick(UUID id, String name, boolean archived, long usageCount) {
	}

	/** @param created true = new (201), false = existing (200) */
	public record Created(Pick tag, boolean created) {
	}

}
