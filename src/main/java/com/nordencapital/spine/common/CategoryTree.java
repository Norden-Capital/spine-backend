package com.nordencapital.spine.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

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
		checkParent(selfId, parentId, selfHasChildren, false, find);
	}

	/**
	 * Same, for drag and drop: an archived parent can be allowed (archived categories
	 * keep working in the manage page).
	 */
	public static void checkParent(UUID selfId, UUID parentId, boolean selfHasChildren, boolean allowArchivedParent,
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
		if (parent.archived() && !allowArchivedParent) {
			throw invalid("parentId: the parent is archived");
		}
		if (selfHasChildren) {
			throw invalid("parentId: this category has subcategories, so it cannot go under another category. "
					+ "Move its subcategories first");
		}
	}

	/**
	 * Manage-page order: each main category (by position, then name) followed by its
	 * subcategories (by position, then name). Subcategories whose parent is missing go last.
	 */
	public static <T> List<T> ordered(List<T> all, Function<T, UUID> id, Function<T, UUID> parentId,
			ToIntFunction<T> position, Function<T, String> name) {
		Comparator<T> order = Comparator.comparingInt(position)
			.thenComparing(name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing((item) -> id.apply(item).toString());
		Map<UUID, List<T>> children = new HashMap<>();
		List<T> mains = new ArrayList<>();
		Set<UUID> ids = new HashSet<>();
		for (T item : all) {
			ids.add(id.apply(item));
		}
		List<T> orphans = new ArrayList<>();
		for (T item : all) {
			UUID parent = parentId.apply(item);
			if (parent == null) {
				mains.add(item);
			}
			else if (ids.contains(parent)) {
				children.computeIfAbsent(parent, (k) -> new ArrayList<>()).add(item);
			}
			else {
				orphans.add(item);
			}
		}
		mains.sort(order);
		orphans.sort(order);
		List<T> result = new ArrayList<>();
		for (T main : mains) {
			result.add(main);
			List<T> subs = new ArrayList<>(children.getOrDefault(id.apply(main), List.of()));
			subs.sort(order);
			result.addAll(subs);
		}
		result.addAll(orphans);
		return result;
	}

	/**
	 * New order of the siblings after a move: {@code siblings} (without the moved one, in
	 * their current order) with {@code movedId} inserted at {@code position} (clamped).
	 */
	public static List<UUID> insertAt(List<UUID> siblings, UUID movedId, int position) {
		List<UUID> result = new ArrayList<>(siblings);
		result.remove(movedId);
		result.add(Math.max(0, Math.min(position, result.size())), movedId);
		return result;
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	/** The parts of a category the rules need. */
	public record Node(UUID id, UUID parentId, boolean archived) {
	}

}
