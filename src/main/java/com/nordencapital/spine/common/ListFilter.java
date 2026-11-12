package com.nordencapital.spine.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * Checked filters of a transaction list (company or wallet). Every field is optional.
 * @param from first date, included (null = no start)
 * @param to last date, included (null = no end)
 * @param q lower-case text to look for (null = no text filter)
 * @param page 0-based
 */
public record ListFilter(LocalDate from, LocalDate to, String q, Set<UUID> categoryIds, String type,
		UUID createdBy, BigDecimal minAmount, BigDecimal maxAmount, int page, int size) {

	public static final int DEFAULT_SIZE = 50;

	public static final int MAX_SIZE = 100;

	private static final Pattern MONEY = Pattern.compile("(0|[1-9][0-9]{0,9})(\\.[0-9]{1,2})?");

	/**
	 * Parses the raw query parameters.
	 * @param thisMonth the month to use when none of month/from/to is given
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static ListFilter parse(Params raw, YearMonth thisMonth) {
		LocalDate from = date("from", raw.from());
		LocalDate to = date("to", raw.to());
		if (from == null && to == null) {
			YearMonth month = (raw.month() != null && !raw.month().isBlank()) ? month(raw.month()) : thisMonth;
			from = month.atDay(1);
			to = month.atEndOfMonth();
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw invalid("from: must not be after to");
		}
		String q = (raw.q() != null && !raw.q().isBlank()) ? raw.q().strip() : null;
		if (q != null && q.length() > 200) {
			throw invalid("q: at most 200 characters");
		}
		String type = choice("type", raw.type(), "EXPENSE", "INCOME");
		UUID createdBy = (raw.createdBy() != null && !raw.createdBy().isBlank()) ? uuid("createdBy", raw.createdBy())
				: null;
		BigDecimal min = money("minAmount", raw.minAmount());
		BigDecimal max = money("maxAmount", raw.maxAmount());
		if (min != null && max != null && min.compareTo(max) > 0) {
			throw invalid("minAmount: must not be more than maxAmount");
		}
		int page = number("page", raw.page(), 0, 0, Integer.MAX_VALUE);
		// size=0: only totalItems and totals (no items), for a live "Show N results".
		int size = number("size", raw.size(), DEFAULT_SIZE, 0, MAX_SIZE);
		return new ListFilter(from, to, q, uuids("categoryIds", raw.categoryIds()), type, createdBy, min, max, page, size);
	}

	private static LocalDate date(String field, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.strip());
		}
		catch (DateTimeParseException ex) {
			throw invalid(field + ": must be a date like 2026-03-31");
		}
	}

	private static YearMonth month(String value) {
		try {
			return YearMonth.parse(value.strip());
		}
		catch (DateTimeParseException ex) {
			throw invalid("month: must be like 2026-03");
		}
	}

	private static String choice(String field, String value, String... allowed) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (String option : allowed) {
			if (option.equals(value.strip())) {
				return option;
			}
		}
		throw invalid(field + ": must be one of " + String.join(", ", allowed));
	}

	private static BigDecimal money(String field, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (!MONEY.matcher(value.strip()).matches()) {
			throw invalid(field + ": must be an amount like 123.45");
		}
		return new BigDecimal(value.strip());
	}

	private static int number(String field, String value, int fallback, int min, int max) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			int number = Integer.parseInt(value.strip());
			if (number < min || number > max) {
				throw invalid(field + ": must be from " + min + " to " + max);
			}
			return number;
		}
		catch (NumberFormatException ex) {
			throw invalid(field + ": must be a whole number");
		}
	}

	private static Set<UUID> uuids(String field, List<String> values) {
		Set<UUID> ids = new LinkedHashSet<>();
		if (values == null) {
			return ids;
		}
		List<String> parts = new ArrayList<>();
		values.forEach((value) -> parts.addAll(List.of(value.split(","))));
		for (String part : parts) {
			if (!part.isBlank()) {
				ids.add(uuid(field, part));
			}
		}
		if (ids.size() > 50) {
			throw invalid(field + ": at most 50 ids");
		}
		return ids;
	}

	private static UUID uuid(String field, String value) {
		try {
			return UUID.fromString(value.strip());
		}
		catch (IllegalArgumentException ex) {
			throw invalid(field + ": must be ids");
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	/** The raw query parameters, as sent. */
	public record Params(String from, String to, String month, String q, List<String> categoryIds, String type, String createdBy, String minAmount, String maxAmount,
			String page, String size) {
	}

}
