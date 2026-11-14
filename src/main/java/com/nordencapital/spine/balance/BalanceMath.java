package com.nordencapital.spine.balance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The guess of a balance (no database). Cut dates are the series {@code anchor + k steps}
 * (k = 0, 1, 2...): every N days / weeks / months / years from the anchor, or "N times a
 * month" spread evenly (anchor + round(k × 30.44 / N) days). The start amount is the
 * balance at the END of the start date, so only the cut dates after it count.
 *
 * @param unit DAY, WEEK, MONTH, YEAR or TIMES_A_MONTH
 * @param every every N units (TIMES_A_MONTH: N times a month)
 * @param anchor the first cut date of the series ("next cut" in the form)
 */
record BalanceMath(String unit, int every, LocalDate anchor) {

	static final String TIMES_A_MONTH = "TIMES_A_MONTH";

	/** Days in an average month. */
	static final double MONTH_DAYS = 30.44;

	/** Weeks in an average month. */
	static final BigDecimal MONTH_WEEKS = new BigDecimal("4.35");

	/** Past this many cuts we stop looking (thousands of years away). */
	static final long MAX_CUTS = 2_000_000L;

	/** The k-th cut date (k = 0 is the anchor). */
	LocalDate cut(long k) {
		return switch (this.unit) {
			case "DAY" -> this.anchor.plusDays(k * this.every);
			case "WEEK" -> this.anchor.plusWeeks(k * this.every);
			case "MONTH" -> this.anchor.plusMonths(k * this.every);
			case "YEAR" -> this.anchor.plusYears(k * this.every);
			case TIMES_A_MONTH -> this.anchor.plusDays(Math.round(k * MONTH_DAYS / this.every));
			default -> throw new IllegalStateException("Unknown unit " + this.unit);
		};
	}

	/** The cut date one step after {@code date} if the series started there (the default "next cut"). */
	static LocalDate oneStepAfter(String unit, int every, LocalDate date) {
		return new BalanceMath(unit, every, date).cut(1);
	}

	/** Index of the first cut date strictly after {@code date}. */
	long firstAfter(LocalDate date) {
		if (this.anchor.isAfter(date)) {
			return 0;
		}
		long low = 0;
		long high = 1;
		while (!cut(high).isAfter(date)) {
			low = high;
			high = Math.min(high * 2, MAX_CUTS);
			if (low == MAX_CUTS) {
				return MAX_CUTS;
			}
		}
		// cut(low) <= date < cut(high)
		while (high - low > 1) {
			long mid = (low + high) >>> 1;
			if (cut(mid).isAfter(date)) {
				high = mid;
			}
			else {
				low = mid;
			}
		}
		return high;
	}

	/** How many cut dates are in (after, upTo]. */
	long cutsBetween(LocalDate after, LocalDate upTo) {
		if (!upTo.isAfter(after)) {
			return 0;
		}
		return firstAfter(upTo) - firstAfter(after);
	}

	/** The first cut date after {@code date}. */
	LocalDate nextCutAfter(LocalDate date) {
		return cut(firstAfter(date));
	}

	/** The n-th cut date (n = 1, 2...) after {@code date}, or null if too far away. */
	LocalDate nthCutAfter(LocalDate date, long n) {
		long index = firstAfter(date) + n - 1;
		return (n < 1 || index >= MAX_CUTS) ? null : cut(index);
	}

	/** Guessed balance on {@code date}: start − cuts × cut, never below 0. */
	static BigDecimal guess(BigDecimal start, BigDecimal cut, long cuts) {
		BigDecimal left = start.subtract(cut.multiply(BigDecimal.valueOf(cuts)));
		return (left.signum() < 0) ? BigDecimal.ZERO.setScale(2) : left.setScale(2);
	}

	BigDecimal guess(BigDecimal start, LocalDate startDate, BigDecimal cut, LocalDate date) {
		return guess(start, cut, cutsBetween(startDate, date));
	}

	/**
	 * The cut date that takes the guess to $0: the start date itself when the start is 0,
	 * else the ceil(start / cut)-th cut after it (null if too far away).
	 */
	LocalDate emptyDate(BigDecimal start, LocalDate startDate, BigDecimal cut) {
		if (start.signum() <= 0) {
			return startDate;
		}
		return nthCutAfter(startDate, start.divide(cut, 0, RoundingMode.CEILING).longValueExact());
	}

	/**
	 * The first date the guess is under the limit: the start date when it already is,
	 * else the (floor((start − limit) / cut) + 1)-th cut after it.
	 */
	LocalDate belowLimitDate(BigDecimal start, LocalDate startDate, BigDecimal cut, BigDecimal limit) {
		if (start.compareTo(limit) < 0) {
			return startDate;
		}
		long n = start.subtract(limit).divide(cut, 0, RoundingMode.FLOOR).longValueExact() + 1;
		return nthCutAfter(startDate, n);
	}

	/**
	 * Cost a month: every N days × 30.44 / N, every N weeks × 4.35 / N, every N months / N,
	 * every N years / (12 N), N times a month × N. Rounded to cents.
	 */
	BigDecimal perMonth(BigDecimal cut) {
		BigDecimal n = BigDecimal.valueOf(this.every);
		BigDecimal value = switch (this.unit) {
			case "DAY" -> cut.multiply(BigDecimal.valueOf(MONTH_DAYS)).divide(n, 10, RoundingMode.HALF_UP);
			case "WEEK" -> cut.multiply(MONTH_WEEKS).divide(n, 10, RoundingMode.HALF_UP);
			case "MONTH" -> cut.divide(n, 10, RoundingMode.HALF_UP);
			case "YEAR" -> cut.divide(n.multiply(BigDecimal.valueOf(12)), 10, RoundingMode.HALF_UP);
			case TIMES_A_MONTH -> cut.multiply(n);
			default -> throw new IllegalStateException("Unknown unit " + this.unit);
		};
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * RED: empty, or under the low limit. AMBER: empty within the warn days, or the next
	 * cut takes it under the limit. GREEN: OK.
	 */
	static String urgency(BigDecimal guess, BigDecimal cut, BigDecimal limit, LocalDate emptyDate, int warnDays,
			LocalDate today) {
		if (guess.signum() <= 0 || (limit != null && guess.compareTo(limit) < 0)) {
			return "RED";
		}
		boolean soon = emptyDate != null && ChronoUnit.DAYS.between(today, emptyDate) <= warnDays;
		boolean crossesLimit = limit != null && guess.subtract(cut).compareTo(limit) < 0;
		return (soon || crossesLimit) ? "AMBER" : "GREEN";
	}

}
