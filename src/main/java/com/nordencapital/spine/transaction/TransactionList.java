package com.nordencapital.spine.transaction;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One month of transactions and the totals per currency.
 * @param totals always has USD and MAD
 */
public record TransactionList(List<TransactionResponse> items, Map<String, Totals> totals) {

	/**
	 * Money strings like "123.45".
	 * @param totalIn total INCOME (JSON "in")
	 * @param totalOut total EXPENSE (JSON "out")
	 * @param net in - out (can be negative)
	 */
	public record Totals(@JsonProperty("in") String totalIn, @JsonProperty("out") String totalOut, String net) {
	}

}
