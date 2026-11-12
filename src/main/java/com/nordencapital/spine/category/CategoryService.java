package com.nordencapital.spine.category;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Actors;

/**
 * Expense categories. Everyone reads the active ones; only managers (OWNER, ADMIN)
 * create, change, archive and unarchive. Nothing is ever deleted.
 */
@Service
public class CategoryService {

	/**
	 * Categories at or above this sort order (the seeded "Other") always stay last. New
	 * categories are added just before them.
	 */
	static final int LAST_SORT_ORDER = 1000;

	private static final int SORT_STEP = 10;

	private final CategoryRepository categories;

	private final Actors actors;

	private final Clock clock;

	public CategoryService(CategoryRepository categories, Actors actors, Clock clock) {
		this.categories = categories;
		this.actors = actors;
		this.clock = clock;
	}

	/** Active categories, in display order. */
	@Transactional(readOnly = true)
	public List<CategoryResponse> active(AuthUser principal) {
		this.actors.active(principal);
		return this.categories.findAllByArchivedFalseOrderBySortOrderAscNameAsc()
			.stream()
			.map(CategoryResponse::from)
			.toList();
	}

	/** All categories, archived too (managers only). */
	@Transactional(readOnly = true)
	public List<CategoryResponse> all(AuthUser principal) {
		this.actors.manager(principal);
		return this.categories.findAllByOrderBySortOrderAscNameAsc().stream().map(CategoryResponse::from).toList();
	}

	/**
	 * @param name already trimmed and checked (1 to 40 characters)
	 * @throws ApiException 409 {@code name_taken}, 400 {@code validation_error} (icon)
	 */
	@Transactional
	public CategoryResponse create(AuthUser principal, String name, String icon) {
		this.actors.manager(principal);
		checkIcon(icon);
		if (this.categories.findByNameIgnoreCase(name).isPresent()) {
			throw nameTaken();
		}
		Integer max = this.categories.maxSortOrderBelow(LAST_SORT_ORDER);
		int sortOrder = Math.min(((max != null) ? max : 0) + SORT_STEP, LAST_SORT_ORDER - 1);
		Category category = Category.create(name, icon, sortOrder, this.clock.instant());
		return CategoryResponse.from(saveFlush(category));
	}

	/**
	 * Changes the name and/or the icon (null = keep).
	 * @throws ApiException 404 {@code category_not_found}, 409 {@code name_taken}, 400
	 * {@code validation_error} (icon)
	 */
	@Transactional
	public CategoryResponse update(AuthUser principal, UUID id, String name, String icon) {
		this.actors.manager(principal);
		Category category = find(id);
		if (name != null) {
			boolean takenByOther = this.categories.findByNameIgnoreCase(name)
				.filter((other) -> !other.getId().equals(id))
				.isPresent();
			if (takenByOther) {
				throw nameTaken();
			}
			category.rename(name, this.clock.instant());
		}
		if (icon != null) {
			checkIcon(icon);
			category.changeIcon(icon, this.clock.instant());
		}
		return CategoryResponse.from(saveFlush(category));
	}

	/**
	 * Archives or unarchives (no change if already in that state).
	 * @throws ApiException 404 {@code category_not_found}
	 */
	@Transactional
	public CategoryResponse setArchived(AuthUser principal, UUID id, boolean archived) {
		this.actors.manager(principal);
		Category category = find(id);
		if (category.isArchived() != archived) {
			category.setArchived(archived, this.clock.instant());
		}
		return CategoryResponse.from(category);
	}

	private Category saveFlush(Category category) {
		try {
			return this.categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			// Two managers saved the same name at the same time.
			throw nameTaken();
		}
	}

	private Category find(UUID id) {
		return this.categories.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "category_not_found", "Category not found"));
	}

	private static void checkIcon(String icon) {
		if (!CategoryIcons.ALLOWED.contains(icon)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"icon: must be one of the icons from GET /api/categories/icons");
		}
	}

	private static ApiException nameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "name_taken", "A category with this name already exists");
	}

}
