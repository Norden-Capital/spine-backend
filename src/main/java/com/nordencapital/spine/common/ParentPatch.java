package com.nordencapital.spine.common;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * The "parentId" of a PATCH body: left out (no change), null (top level), or an id.
 * @param given false when the field was left out
 * @param parentId the new parent, or null for top level
 */
public record ParentPatch(boolean given, UUID parentId) {

	public static final ParentPatch NOT_GIVEN = new ParentPatch(false, null);

	/**
	 * @param node the JSON value (Java null = left out; a JSON null = top level)
	 * @throws ApiException 400 {@code validation_error} if it is not an id
	 */
	public static ParentPatch of(JsonNode node) {
		if (node == null) {
			return NOT_GIVEN;
		}
		if (node.isNull()) {
			return new ParentPatch(true, null);
		}
		try {
			return new ParentPatch(true, UUID.fromString(node.asString()));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "parentId: must be a category id or null");
		}
	}

}
