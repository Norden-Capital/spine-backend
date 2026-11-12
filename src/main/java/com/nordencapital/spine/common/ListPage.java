package com.nordencapital.spine.common;

import java.util.List;

/**
 * One page of a filtered transaction list.
 * @param totals in/out/net of ALL matching items (not only this page)
 * @param page 0-based
 * @param totalItems all matching items
 */
public record ListPage<T>(List<T> items, Totals totals, int page, int size, long totalItems,
		boolean hasNext) {
}
