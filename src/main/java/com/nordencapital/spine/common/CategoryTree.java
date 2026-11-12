package com.nordencapital.spine.common;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;

/**
 * Rules for subcategories (company and wallet): one level only.
 */
public final class CategoryTree {

	private CategoryTree() {
	}

	/**
	 * Checks that {@code parentId} can be the parent of category {@code selfId}.
	 * @param selfId null for a new category
	 * @param selfHasChildren true if the category already has subcategories
	 * @param find loads a category (of the same owner) by id
	 * @throws ApiException 400 {@code validation_error}
	 */
	public static void checkParent(UUID selfId, UUID parentId, boolean selfHasChildren,
			Function<UUID, Optional<Node>> find) {
		if (parentId == null) {
			return;
		}
		if (parentId.equals(selfId)) {
			throw invalid("parentId: a category cannot be its own parent");
		}
		Node parent = find.apply(parentId).orElseThrow(() -> invalid("parentId: unknown category"));
		if (parent.parentId() != null) {
			throw invalid("parentId: the parent is already a subcategory (one level only)");
		}
		if (parent.archived()) {
			throw invalid("parentId: the parent is archived");
		}
		if (selfHasChildren) {
			throw invalid("parentId: this category has subcategories, so it cannot become one");
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	/** The parts of a category the rules need. */
	public record Node(UUID id, UUID parentId, boolean archived) {
	}

}
