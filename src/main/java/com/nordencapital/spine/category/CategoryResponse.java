package com.nordencapital.spine.category;

import java.util.Map;
import java.util.UUID;

/**
 * @param parentId null = top level
 * @param website bare domain like "claude.ai", or null
 * @param logoUrl path of the website's logo, or null (show the icon)
 */
public record CategoryResponse(UUID id, String name, String icon, boolean archived, UUID parentId, String website,
		String logoUrl) {

	static CategoryResponse from(Category category, Map<String, String> logoUrls) {
		return new CategoryResponse(category.getId(), category.getName(), category.getIcon(), category.isArchived(),
				category.getParentId(), category.getWebsite(), logoUrls.get(category.getWebsite()));
	}

}
