package com.nordencapital.spine.tag;

import java.util.UUID;

/**
 * A tag in a picker, with how often it was used in the last 90 days.
 */
public record TagPick(UUID id, String name, boolean archived, long usageCount) {
}
