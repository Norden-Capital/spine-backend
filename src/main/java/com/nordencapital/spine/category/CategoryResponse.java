package com.nordencapital.spine.category;

import java.util.Map;
import java.util.UUID;

/**
 * @param kind EXPENSE or INCOME
 * @param parentId null = top level
 * @param position 0-based order among its siblings
 * @param website bare domain like "claude.ai", or null
 * @param logoUrls logo image URLs to try in the browser, in order; empty = show the icon
 */
public record CategoryResponse(UUID id, String name, String icon, String kind, boolean archived, UUID parentId,
		int position,
		String website, java.util.List<String> logoUrls) {

	static CategoryResponse from(Category category, Map<String, java.util.List<String>> logoUrls) {
		return new CategoryResponse(category.getId(), category.getName(), category.getIcon(), category.getKind(),
				category.isArchived(),
				category.getParentId(), category.getPosition(), category.getWebsite(),
				com.nordencapital.spine.logo.LogoService.chain(logoUrls, category.getWebsite()));
	}

}
