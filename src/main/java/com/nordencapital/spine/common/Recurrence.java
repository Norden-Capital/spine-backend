package com.nordencapital.spine.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;

/**
 * Dates of a repeating series: every {@code every} DAY, WEEK, MONTH or YEAR from a start
 * date (e.g. MONTH/3 = every 3 months). Each date is counted from the start, so a 31st
 * falls on the last day of shorter months and comes back to the 31st (no drift). Used by
 * the wallet recurring items and the company repeat expenses.
 */
public final class Recurrence {

	/** Allowed units (a regex for request validation). */
	public static final String UNITS = "DAY|WEEK|MONTH|YEAR";

	private Recurrence() {
	}

	/** Largest "every" for a unit. */
	public static int maxEvery(String unit) {
		return switch (unit) {
			case "DAY" -> 365;
			case "WEEK" -> 52;
			case "MONTH" -> 24;
			default -> 5;
		};
	}

	/**
	 * @throws ApiException 400 {@code validation_error} if {@code every} is out of range
	 * for the unit
	 */
	public static void check(String unit, int every) {
		if (every < 1 || every > maxEvery(unit)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"every: for " + unit + " must be 1 to " + maxEvery(unit));
		}
	}

	/** The first date of the series on or after {@code date}. */
	public static LocalDate nextOnOrAfter(LocalDate start, String unit, int every, LocalDate date) {
		if (!start.isBefore(date)) {
			return start;
		}
		// Start one step early (the estimate can be one step too far), then walk forward.
		long k = Math.max(0, chrono(unit).between(start, date) / every - 1);
		LocalDate candidate = occurrence(start, unit, every, k);
		while (candidate.isBefore(date)) {
			k++;
			candidate = occurrence(start, unit, every, k);
		}
		return candidate;
	}

	/** True if {@code date} is one of the dates of the series. */
	public static boolean isSeriesDate(LocalDate start, String unit, int every, LocalDate date) {
		return !date.isBefore(start) && nextOnOrAfter(start, unit, every, date).equals(date);
	}

	/**
	 * The next {@code count} dates of the series on or after {@code from} (stopping after
	 * {@code end}, if given).
	 */
	public static java.util.List<LocalDate> dates(LocalDate start, String unit, int every, LocalDate from,
			LocalDate end, int count) {
		java.util.List<LocalDate> dates = new java.util.ArrayList<>();
		LocalDate date = nextOnOrAfter(start, unit, every, from);
		while (dates.size() < count && (end == null || !date.isAfter(end))) {
			dates.add(date);
			date = nextOnOrAfter(start, unit, every, date.plusDays(1));
		}
		return dates;
	}

	/** First date of the series on or after {@code from} that is not skipped (null after {@code end}). */
	public static LocalDate nextNotSkipped(LocalDate start, String unit, int every, LocalDate from, LocalDate end,
			java.util.Set<LocalDate> skips) {
		LocalDate date = nextOnOrAfter(start, unit, every, from);
		for (int i = 0; i < 1000 && skips.contains(date); i++) {
			date = nextOnOrAfter(start, unit, every, date.plusDays(1));
		}
		return (end != null && date.isAfter(end)) ? null : date;
	}

	/**
	 * Parses a series date sent by the client.
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static LocalDate parseDate(String value) {
		try {
			return LocalDate.parse(value);
		}
		catch (RuntimeException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "date: must be a real date like 2026-03-31");
		}
	}

	/** Body with one date (skip, pause until). */
	public record DateRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(
			regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String date) {
	}

	/**
	 * One of the next dates of a series.
	 * @param status UPCOMING, SKIPPED, PAUSED, DONE (wallet: made; company: saved), DUE
	 * (company reminder waiting)
	 */
	public record Upcoming(LocalDate date, String status) {
	}

	/** The k-th date of the series (k = 0 is the start), always counted from the start. */
	static LocalDate occurrence(LocalDate start, String unit, int every, long k) {
		return start.plus(k * every, chrono(unit));
	}

	private static ChronoUnit chrono(String unit) {
		return switch (unit) {
			case "DAY" -> ChronoUnit.DAYS;
			case "WEEK" -> ChronoUnit.WEEKS;
			case "MONTH" -> ChronoUnit.MONTHS;
			default -> ChronoUnit.YEARS;
		};
	}

}
