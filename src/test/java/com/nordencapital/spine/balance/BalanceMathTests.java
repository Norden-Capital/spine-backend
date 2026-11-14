package com.nordencapital.spine.balance;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guess of a balance: cut dates for each "how often", the guess (never below 0), the
 * empty date, the low-limit date, the monthly cost and the urgency. No database.
 */
class BalanceMathTests {

	private static final LocalDate SAT_14_NOV = LocalDate.of(2026, 11, 14);

	@Test
	void cutDatesForEachFrequency() {
		LocalDate anchor = LocalDate.of(2026, 1, 31);
		assertThat(new BalanceMath("DAY", 3, anchor).cut(2)).isEqualTo(LocalDate.of(2026, 2, 6));
		assertThat(new BalanceMath("WEEK", 2, anchor).cut(1)).isEqualTo(LocalDate.of(2026, 2, 14));
		// Months are counted from the anchor: no drift after a short month.
		BalanceMath monthly = new BalanceMath("MONTH", 1, anchor);
		assertThat(monthly.cut(1)).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(monthly.cut(2)).isEqualTo(LocalDate.of(2026, 3, 31));
		assertThat(new BalanceMath("YEAR", 1, LocalDate.of(2028, 2, 29)).cut(1)).isEqualTo(LocalDate.of(2029, 2, 28));
		// 8 times a month: about every 3.8 days, spread evenly.
		BalanceMath eight = new BalanceMath("TIMES_A_MONTH", 8, LocalDate.of(2026, 11, 16));
		assertThat(eight.cut(1)).isEqualTo(LocalDate.of(2026, 11, 20));
		assertThat(eight.cut(2)).isEqualTo(LocalDate.of(2026, 11, 24));
		assertThat(eight.cut(8)).isEqualTo(LocalDate.of(2026, 12, 16));
		assertThat(eight.cutsBetween(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 15))).isEqualTo(8);
	}

	@Test
	void countsOnlyTheCutsAfterTheStartDate() {
		BalanceMath daily = new BalanceMath("DAY", 1, LocalDate.of(2026, 11, 9));
		// Start Sun 8 Nov: the cuts of 9..14 Nov count (6), the start day itself does not.
		assertThat(daily.cutsBetween(LocalDate.of(2026, 11, 8), SAT_14_NOV)).isEqualTo(6);
		assertThat(daily.cutsBetween(LocalDate.of(2026, 11, 9), SAT_14_NOV)).isEqualTo(5);
		assertThat(daily.cutsBetween(SAT_14_NOV, SAT_14_NOV)).isZero();
		assertThat(daily.cutsBetween(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5))).isZero();
		assertThat(daily.nextCutAfter(SAT_14_NOV)).isEqualTo(LocalDate.of(2026, 11, 15));
		assertThat(daily.nextCutAfter(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 11, 9));
		// Far away still works (binary search).
		assertThat(daily.cutsBetween(LocalDate.of(2026, 11, 8), LocalDate.of(2126, 11, 8))).isEqualTo(36524);
	}

	@Test
	void theGuessGoesDownInStepsAndNeverBelowZero() {
		BalanceMath daily = new BalanceMath("DAY", 1, LocalDate.of(2026, 11, 9));
		LocalDate start = LocalDate.of(2026, 11, 8);
		assertThat(daily.guess(money("80.00"), start, money("6.40"), SAT_14_NOV)).isEqualTo(money("41.60"));
		assertThat(daily.guess(money("80.00"), start, money("6.40"), start)).isEqualTo(money("80.00"));
		assertThat(daily.guess(money("80.00"), start, money("6.40"), LocalDate.of(2026, 12, 31)))
			.isEqualTo(money("0.00"));
		assertThat(BalanceMath.guess(money("10.00"), money("3.00"), 4)).isEqualTo(money("0.00"));
	}

	@Test
	void emptyDateIsTheCutThatReachesZero() {
		BalanceMath daily = new BalanceMath("DAY", 1, LocalDate.of(2026, 11, 9));
		LocalDate start = LocalDate.of(2026, 11, 8);
		// 80 / 6.40 = 12.5: the 13th cut takes it to 0.
		assertThat(daily.emptyDate(money("80.00"), start, money("6.40"))).isEqualTo(LocalDate.of(2026, 11, 21));
		// Exactly 4 cuts.
		assertThat(daily.emptyDate(money("24.00"), start, money("6.00"))).isEqualTo(LocalDate.of(2026, 11, 12));
		// Already 0: empty since the start date.
		assertThat(daily.emptyDate(money("0.00"), start, money("6.00"))).isEqualTo(start);
		BalanceMath eight = new BalanceMath("TIMES_A_MONTH", 8, LocalDate.of(2026, 11, 16));
		// $84, $12 a cut: 7 cuts, the 7th is Wed 9 Dec (the mock said ~10 Dec).
		assertThat(eight.emptyDate(money("84.00"), SAT_14_NOV, money("12.00"))).isEqualTo(LocalDate.of(2026, 12, 9));
		// Too far away: null.
		assertThat(daily.emptyDate(money("9999999999.99"), start, money("0.01"))).isNull();
	}

	@Test
	void belowLimitDate() {
		BalanceMath daily = new BalanceMath("DAY", 1, LocalDate.of(2026, 11, 9));
		LocalDate start = LocalDate.of(2026, 11, 8);
		// 80 - 9 × 6.40 = 22.40 < 25: the 9th cut, Tue 17 Nov.
		assertThat(daily.belowLimitDate(money("80.00"), start, money("6.40"), money("25.00")))
			.isEqualTo(LocalDate.of(2026, 11, 17));
		// Exactly at the limit is not below it.
		assertThat(daily.belowLimitDate(money("30.00"), start, money("5.00"), money("25.00")))
			.isEqualTo(LocalDate.of(2026, 11, 10));
		assertThat(daily.belowLimitDate(money("20.00"), start, money("5.00"), money("25.00"))).isEqualTo(start);
	}

	@Test
	void monthlyCostForEachFrequency() {
		LocalDate anchor = SAT_14_NOV;
		assertThat(new BalanceMath("DAY", 1, anchor).perMonth(money("6.40"))).isEqualTo(money("194.82"));
		assertThat(new BalanceMath("DAY", 2, anchor).perMonth(money("10.00"))).isEqualTo(money("152.20"));
		assertThat(new BalanceMath("WEEK", 1, anchor).perMonth(money("10.00"))).isEqualTo(money("43.50"));
		assertThat(new BalanceMath("WEEK", 2, anchor).perMonth(money("10.00"))).isEqualTo(money("21.75"));
		assertThat(new BalanceMath("MONTH", 3, anchor).perMonth(money("30.00"))).isEqualTo(money("10.00"));
		assertThat(new BalanceMath("YEAR", 1, anchor).perMonth(money("120.00"))).isEqualTo(money("10.00"));
		assertThat(new BalanceMath("YEAR", 2, anchor).perMonth(money("100.00"))).isEqualTo(money("4.17"));
		assertThat(new BalanceMath("TIMES_A_MONTH", 8, anchor).perMonth(money("12.00"))).isEqualTo(money("96.00"));
	}

	@Test
	void defaultNextCutIsOneStepAfterToday() {
		assertThat(BalanceMath.oneStepAfter("DAY", 1, SAT_14_NOV)).isEqualTo(LocalDate.of(2026, 11, 15));
		assertThat(BalanceMath.oneStepAfter("WEEK", 1, SAT_14_NOV)).isEqualTo(LocalDate.of(2026, 11, 21));
		assertThat(BalanceMath.oneStepAfter("MONTH", 1, SAT_14_NOV)).isEqualTo(LocalDate.of(2026, 12, 14));
		assertThat(BalanceMath.oneStepAfter("TIMES_A_MONTH", 2, SAT_14_NOV)).isEqualTo(LocalDate.of(2026, 11, 29));
	}

	@Test
	void urgency() {
		LocalDate today = SAT_14_NOV;
		// Empty, or under the limit: red.
		assertThat(BalanceMath.urgency(money("0.00"), money("5.00"), null, today, 7, today)).isEqualTo("RED");
		assertThat(BalanceMath.urgency(money("20.00"), money("5.00"), money("25.00"), today.plusDays(30), 7, today))
			.isEqualTo("RED");
		// Empty within the warn days, or the next cut goes under the limit: amber.
		assertThat(BalanceMath.urgency(money("41.60"), money("6.40"), money("25.00"), today.plusDays(7), 7, today))
			.isEqualTo("AMBER");
		assertThat(BalanceMath.urgency(money("28.00"), money("5.00"), money("25.00"), today.plusDays(30), 7, today))
			.isEqualTo("AMBER");
		assertThat(BalanceMath.urgency(money("100.00"), money("5.00"), money("25.00"), today.plusDays(20), 7, today))
			.isEqualTo("GREEN");
		assertThat(BalanceMath.urgency(money("100.00"), money("5.00"), null, null, 7, today)).isEqualTo("GREEN");
	}

	private static BigDecimal money(String text) {
		return new BigDecimal(text);
	}

}
