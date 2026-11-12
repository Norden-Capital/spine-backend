package com.nordencapital.spine.category;

import java.util.UUID;

/**
 * A category in a picker, with how often it was used in the last 90 days.
 */
public record CategoryPick(UUID id, String name, String icon, String kind, boolean archived, UUID parentId, int position,
		String website,
		java.util.List<String> logoUrls, long usageCount) {
}
