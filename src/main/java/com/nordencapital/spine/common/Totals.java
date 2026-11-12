package com.nordencapital.spine.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Money totals (USD), as strings like "123.45".
 * @param totalIn total INCOME (JSON "in")
 * @param totalOut total EXPENSE (JSON "out")
 * @param net in - out (can be negative)
 */
public record Totals(@JsonProperty("in") String totalIn, @JsonProperty("out") String totalOut, String net) {

	public static Totals of(BigDecimal in, BigDecimal out) {
		return new Totals(money(in), money(out), money(in.subtract(out)));
	}

	/**
	 * Totals from sums per type (all amounts are USD).
	 * @param sums key "INCOME" or "EXPENSE"
	 */
	public static Totals fromSums(Map<String, BigDecimal> sums) {
		return of(sums.getOrDefault("INCOME", BigDecimal.ZERO), sums.getOrDefault("EXPENSE", BigDecimal.ZERO));
	}

	/** "1234.50" style money text. */
	public static String money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

}
