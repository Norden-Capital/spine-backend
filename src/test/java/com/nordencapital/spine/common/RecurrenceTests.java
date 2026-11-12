package com.nordencapital.spine.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceTests {

	private static LocalDate d(String text) {
		return LocalDate.parse(text);
	}

	private static LocalDate next(String start, String unit, int every, String from) {
		return Recurrence.nextOnOrAfter(d(start), unit, every, d(from));
	}

	@Test
	void everyTwoWeeksAcrossMonthsAndYears() {
		assertThat(next("2026-01-20", "WEEK", 2, "2026-01-01")).isEqualTo(d("2026-01-20"));
		assertThat(next("2026-01-20", "WEEK", 2, "2026-01-20")).isEqualTo(d("2026-01-20"));
		assertThat(next("2026-01-20", "WEEK", 2, "2026-01-21")).isEqualTo(d("2026-02-03"));
		// Month boundary: Jan 20 -> Feb 3 -> Feb 17 -> Mar 3.
		assertThat(next("2026-01-20", "WEEK", 2, "2026-02-18")).isEqualTo(d("2026-03-03"));
		// A week between two dates goes to the next one, never the week in between.
		assertThat(next("2026-01-20", "WEEK", 2, "2026-02-10")).isEqualTo(d("2026-02-17"));
		assertThat(next("2026-01-20", "WEEK", 2, "2027-01-01")).isEqualTo(d("2027-01-05"));
		assertThat(next("2028-02-29", "WEEK", 2, "2028-03-01")).isEqualTo(d("2028-03-14"));
	}

	@Test
	void everyThreeAndSixMonths() {
		assertThat(next("2026-01-15", "MONTH", 3, "2026-01-16")).isEqualTo(d("2026-04-15"));
		assertThat(next("2026-01-15", "MONTH", 3, "2026-10-16")).isEqualTo(d("2027-01-15"));
		assertThat(next("2026-01-15", "MONTH", 6, "2026-01-16")).isEqualTo(d("2026-07-15"));
		assertThat(next("2026-01-15", "MONTH", 6, "2026-07-16")).isEqualTo(d("2027-01-15"));
		// A 31st every 3 months: the last day of shorter months, then back to the 31st.
		assertThat(next("2026-01-31", "MONTH", 3, "2026-02-01")).isEqualTo(d("2026-04-30"));
		assertThat(next("2026-01-31", "MONTH", 3, "2026-05-01")).isEqualTo(d("2026-07-31"));
		assertThat(next("2025-08-31", "MONTH", 6, "2025-09-01")).isEqualTo(d("2026-02-28"));
		assertThat(next("2025-08-31", "MONTH", 6, "2026-03-01")).isEqualTo(d("2026-08-31"));
	}

	@Test
	void monthlyOnThe31stNeverDrifts() {
		assertThat(next("2026-01-31", "MONTH", 1, "2026-02-01")).isEqualTo(d("2026-02-28"));
		assertThat(next("2026-01-31", "MONTH", 1, "2026-03-01")).isEqualTo(d("2026-03-31"));
		assertThat(next("2026-01-31", "MONTH", 1, "2026-04-01")).isEqualTo(d("2026-04-30"));
		assertThat(next("2026-01-31", "MONTH", 1, "2026-05-01")).isEqualTo(d("2026-05-31"));
		assertThat(next("2028-01-31", "MONTH", 1, "2028-02-01")).isEqualTo(d("2028-02-29"));
		// Step by step like the job does (from the day after each date): still the 31st later.
		LocalDate date = d("2026-01-31");
		for (int i = 0; i < 12; i++) {
			date = Recurrence.nextOnOrAfter(d("2026-01-31"), "MONTH", 1, date.plusDays(1));
		}
		assertThat(date).isEqualTo(d("2027-01-31"));
	}

	@Test
	void daysAndYears() {
		assertThat(next("2026-01-01", "DAY", 10, "2026-01-02")).isEqualTo(d("2026-01-11"));
		assertThat(next("2026-12-25", "DAY", 1, "2027-01-01")).isEqualTo(d("2027-01-01"));
		assertThat(next("2024-02-29", "YEAR", 1, "2025-01-01")).isEqualTo(d("2025-02-28"));
		assertThat(next("2024-02-29", "YEAR", 1, "2028-01-01")).isEqualTo(d("2028-02-29"));
		assertThat(next("2026-03-01", "YEAR", 2, "2026-03-02")).isEqualTo(d("2028-03-01"));
	}

	@Test
	void neverSkipsADate() {
		String[][] repeats = { { "DAY", "3" }, { "WEEK", "1" }, { "WEEK", "2" }, { "MONTH", "1" }, { "MONTH", "3" } };
		LocalDate start = d("2026-12-25");
		for (String[] repeat : repeats) {
			int every = Integer.parseInt(repeat[1]);
			for (int day = 0; day < 400; day++) {
				LocalDate from = start.plusDays(day);
				LocalDate found = Recurrence.nextOnOrAfter(start, repeat[0], every, from);
				// No date of the series lies between "from" and the date found.
				LocalDate before = start;
				long k = 0;
				while (before.isBefore(from)) {
					k++;
					before = Recurrence.occurrence(start, repeat[0], every, k);
				}
				assertThat(found).as(repeat[0] + "/" + every + " from " + from).isEqualTo(before);
			}
		}
		assertThat(ChronoUnit.DAYS.between(d("2026-01-20"), next("2026-01-20", "WEEK", 2, "2027-06-01")) % 14).isZero();
	}

	@Test
	void limitsPerUnit() {
		Recurrence.check("DAY", 365);
		Recurrence.check("WEEK", 52);
		Recurrence.check("MONTH", 24);
		Recurrence.check("YEAR", 5);
		assertThatThrownBy(() -> Recurrence.check("DAY", 366)).hasMessageContaining("every: for DAY must be 1 to 365");
		assertThatThrownBy(() -> Recurrence.check("WEEK", 53)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Recurrence.check("MONTH", 25)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Recurrence.check("YEAR", 6)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Recurrence.check("MONTH", 0)).isInstanceOf(ApiException.class);
	}

}
