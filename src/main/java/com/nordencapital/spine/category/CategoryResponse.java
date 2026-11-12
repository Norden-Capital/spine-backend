package com.nordencapital.spine.category;

import java.util.UUID;

/**
 * @param parentId null = top level
 */
public record CategoryResponse(UUID id, String name, String icon, boolean archived, UUID parentId) {

	static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getName(), category.getIcon(), category.isArchived(),
				category.getParentId());
	}

}
