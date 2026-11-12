package com.nordencapital.spine.tag;

import java.time.Instant;
import java.util.UUID;

/**
 * A tag in the admin list. Usage counts come with expenses (module 4).
 */
public record AdminTagResponse(UUID id, String name, boolean archived, Instant createdAt) {

	static AdminTagResponse from(Tag tag) {
		return new AdminTagResponse(tag.getId(), tag.getName(), tag.isArchived(), tag.getCreatedAt());
	}

}
