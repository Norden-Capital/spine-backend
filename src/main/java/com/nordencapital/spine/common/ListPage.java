package com.nordencapital.spine.common;

import java.util.List;
import java.util.Map;

/**
 * One page of a filtered transaction list.
 * @param totals totals of ALL matching items (not only this page), always USD and MAD
 * @param page 0-based
 * @param totalItems all matching items
 */
public record ListPage<T>(List<T> items, Map<String, Totals> totals, int page, int size, long totalItems,
		boolean hasNext) {
}
