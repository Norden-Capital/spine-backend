package com.nordencapital.spine.category;

import java.util.Map;
import java.util.UUID;

/**
 * @param kind EXPENSE or INCOME
 * @param parentId null = top level
 * @param position 0-based order among its siblings
 * @param website bare domain like "claude.ai", or null
 * @param logoUrl path of the website's logo, or null (show the icon)
 */
public record CategoryResponse(UUID id, String name, String icon, String kind, boolean archived, UUID parentId,
		int position,
		String website, String logoUrl) {

	static CategoryResponse from(Category category, Map<String, String> logoUrls) {
		return new CategoryResponse(category.getId(), category.getName(), category.getIcon(), category.getKind(),
				category.isArchived(),
				category.getParentId(), category.getPosition(), category.getWebsite(),
				logoUrls.get(category.getWebsite()));
	}

}
