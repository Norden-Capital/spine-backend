package com.nordencapital.spine.category;

import java.util.UUID;

/**
 * A category in a picker, with how often it was used in the last 90 days.
 */
public record CategoryPick(UUID id, String name, String icon, boolean archived, UUID parentId, long usageCount) {
}
