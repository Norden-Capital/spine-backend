package com.nordencapital.spine.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Money totals of one currency, as strings like "123.45".
 * @param totalIn total INCOME (JSON "in")
 * @param totalOut total EXPENSE (JSON "out")
 * @param net in - out (can be negative)
 */
public record Totals(@JsonProperty("in") String totalIn, @JsonProperty("out") String totalOut, String net) {

	public static final List<String> CURRENCIES = List.of("USD", "MAD");

	public static Totals of(BigDecimal in, BigDecimal out) {
		return new Totals(money(in), money(out), money(in.subtract(out)));
	}

	/**
	 * USD and MAD totals from sums per (currency, type).
	 * @param sums key "USD:INCOME" etc.
	 */
	public static Map<String, Totals> perCurrency(Map<String, BigDecimal> sums) {
		Map<String, Totals> totals = new LinkedHashMap<>();
		for (String currency : CURRENCIES) {
			totals.put(currency, of(sums.getOrDefault(currency + ":INCOME", BigDecimal.ZERO),
					sums.getOrDefault(currency + ":EXPENSE", BigDecimal.ZERO)));
		}
		return totals;
	}

	/** "1234.50" style money text. */
	public static String money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

}
