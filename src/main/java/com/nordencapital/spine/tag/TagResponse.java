package com.nordencapital.spine.tag;

import java.util.UUID;

public record TagResponse(UUID id, String name, boolean archived) {

	static TagResponse from(Tag tag) {
		return new TagResponse(tag.getId(), tag.getName(), tag.isArchived());
	}

}
