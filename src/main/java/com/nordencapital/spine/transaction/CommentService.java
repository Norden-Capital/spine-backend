package com.nordencapital.spine.transaction;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * Comments on company transactions (not trashed). Every user can read and add; the
 * author and the OWNER can delete. Comments are not in the history.
 */
@Service
public class CommentService {

	private final JdbcTemplate jdbc;

	private final TransactionRepository transactions;

	private final TransactionViews views;

	private final Actors actors;

	private final Clock clock;

	public CommentService(JdbcTemplate jdbc, TransactionRepository transactions, TransactionViews views, Actors actors,
			Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.views = views;
		this.actors = actors;
		this.clock = clock;
	}

	/** Oldest first. */
	@Transactional(readOnly = true)
	public List<CommentResponse> list(AuthUser principal, UUID transactionId) {
		User actor = this.actors.active(principal);
		activeTransaction(transactionId);
		List<Row> rows = this.jdbc.query(
				"SELECT id, user_id, text, created_at FROM transaction_comments WHERE transaction_id = ? "
						+ "ORDER BY created_at, id",
				(rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
						rs.getString("text"), rs.getTimestamp("created_at").toInstant()),
				transactionId);
		Map<UUID, User> users = this.views.usersById(rows.stream().map(Row::userId).toList());
		return rows.stream().map((row) -> toResponse(row, users, actor)).toList();
	}

	/**
	 * @param text already trimmed and checked (1 to 500)
	 * @throws ApiException 404 {@code transaction_not_found}
	 */
	@Transactional
	public CommentResponse add(AuthUser principal, UUID transactionId, String text) {
		User actor = this.actors.active(principal);
		activeTransaction(transactionId);
		Row row = new Row(UUID.randomUUID(), actor.getId(), text, this.clock.instant());
		this.jdbc.update(
				"INSERT INTO transaction_comments (id, transaction_id, user_id, text, created_at) VALUES (?, ?, ?, ?, ?)",
				row.id(), transactionId, row.userId(), row.text(), Timestamp.from(row.createdAt()));
		return toResponse(row, Map.of(actor.getId(), actor), actor);
	}

	/**
	 * @throws ApiException 404 {@code transaction_not_found} or {@code comment_not_found},
	 * 403 {@code forbidden} (not the author, not the OWNER)
	 */
	@Transactional
	public void delete(AuthUser principal, UUID transactionId, UUID commentId) {
		User actor = this.actors.active(principal);
		activeTransaction(transactionId);
		UUID author = this.jdbc
			.queryForList("SELECT user_id FROM transaction_comments WHERE id = ? AND transaction_id = ?", UUID.class,
					commentId, transactionId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "comment_not_found", "Comment not found"));
		if (!author.equals(actor.getId()) && !TransactionViews.isOwner(actor)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
					"Only the author or the owner can delete this comment");
		}
		this.jdbc.update("DELETE FROM transaction_comments WHERE id = ?", commentId);
	}

	private void activeTransaction(UUID id) {
		this.transactions.findById(id)
			.filter((tx) -> !tx.isTrashed())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction not found"));
	}

	private static CommentResponse toResponse(Row row, Map<UUID, User> users, User actor) {
		boolean canDelete = row.userId().equals(actor.getId()) || TransactionViews.isOwner(actor);
		return new CommentResponse(row.id(), row.text(), TransactionViews.userRef(users, row.userId()), row.createdAt(),
				canDelete);
	}

	private record Row(UUID id, UUID userId, String text, Instant createdAt) {
	}

	/**
	 * @param canDelete true if the caller may delete it
	 */
	public record CommentResponse(UUID id, String text, UserRef author, Instant createdAt, boolean canDelete) {
	}

}
