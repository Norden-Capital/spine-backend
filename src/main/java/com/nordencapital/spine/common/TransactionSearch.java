package com.nordencapital.spine.common;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs a filtered, paged transaction list in SQL (company or wallet tables): the ids of
 * one page, the count and the totals of all matches. Values always go in as
 * parameters, never into the SQL text.
 */
public final class TransactionSearch {

	private final String transactions;

	private final String categories;

	private final String comments;

	/**
	 * @param transactions table with id, type, amount, date, description,
	 * category_id, created_at
	 * @param categories table with id, parent_id
	 * @param comments table with transaction_id, text (null = no comment search)
	 */
	public TransactionSearch(String transactions, String categories, String comments) {
		this.transactions = transactions;
		this.categories = categories;
		this.comments = comments;
	}

	/**
	 * @param baseWhere fixed condition on {@code t} (e.g. "t.deleted_at IS NULL")
	 * @param baseArgs its parameters
	 */
	public Result run(JdbcTemplate jdbc, ListFilter filter, String baseWhere, List<Object> baseArgs,
			String createdByColumn) {
		List<Object> args = new ArrayList<>(baseArgs);
		StringBuilder where = new StringBuilder(baseWhere);
		if (filter.from() != null) {
			where.append(" AND t.date >= ?");
			args.add(Date.valueOf(filter.from()));
		}
		if (filter.to() != null) {
			where.append(" AND t.date <= ?");
			args.add(Date.valueOf(filter.to()));
		}
		if (filter.q() != null) {
			String like = Like.contains(filter.q());
			where.append(" AND (lower(t.description) LIKE ? ESCAPE '\\' OR t.website LIKE ? ESCAPE '\\'");
			args.add(like);
			args.add(like);
			if (this.comments != null) {
				where.append(" OR EXISTS (SELECT 1 FROM ").append(this.comments)
					.append(" c WHERE c.transaction_id = t.id AND lower(c.text) LIKE ? ESCAPE '\\')");
				args.add(like);
			}
			where.append(")");
		}
		if (!filter.categoryIds().isEmpty()) {
			String in = placeholders(filter.categoryIds());
			where.append(" AND (t.category_id IN (").append(in).append(") OR t.category_id IN (SELECT id FROM ")
				.append(this.categories).append(" WHERE parent_id IN (").append(in).append(")))");
			args.addAll(filter.categoryIds());
			args.addAll(filter.categoryIds());
		}
		if (filter.type() != null) {
			where.append(" AND t.type = ?");
			args.add(filter.type());
		}
		if (filter.createdBy() != null && createdByColumn != null) {
			where.append(" AND t.").append(createdByColumn).append(" = ?");
			args.add(filter.createdBy());
		}
		if (filter.minAmount() != null) {
			where.append(" AND t.amount >= ?");
			args.add(filter.minAmount());
		}
		if (filter.maxAmount() != null) {
			where.append(" AND t.amount <= ?");
			args.add(filter.maxAmount());
		}
		String from = " FROM " + this.transactions + " t WHERE " + where;

		long count = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args.toArray());
		Map<String, BigDecimal> sums = new HashMap<>();
		jdbc.query("SELECT t.type, sum(t.amount) AS total" + from + " GROUP BY t.type",
				(rs) -> {
					sums.put(rs.getString("type"), rs.getBigDecimal("total"));
				}, args.toArray());
		if (filter.size() == 0) {
			return new Result(List.of(), count, Totals.fromSums(sums)); // count and totals only
		}
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(filter.size());
		pageArgs.add((long) filter.page() * filter.size());
		List<UUID> ids = jdbc.queryForList(
				"SELECT t.id" + from + " ORDER BY t.date DESC, t.created_at DESC, t.id LIMIT ? OFFSET ?", UUID.class,
				pageArgs.toArray());
		return new Result(ids, count, Totals.fromSums(sums));
	}

	private static String placeholders(Collection<?> values) {
		return values.stream().map((value) -> "?").collect(Collectors.joining(","));
	}

	/**
	 * @param ids the ids of this page, in list order
	 * @param count all matching items
	 */
	public record Result(List<UUID> ids, long count, Totals totals) {

		public boolean hasNext(ListFilter filter) {
			return filter.size() > 0 && (long) (filter.page() + 1) * filter.size() < this.count;
		}

	}

}
