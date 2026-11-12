package com.nordencapital.spine.category;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.CategoryTree;
import com.nordencapital.spine.common.ParentPatch;
import com.nordencapital.spine.user.Actors;

/**
 * Expense categories. Everyone reads the active ones (most used first); only managers
 * (OWNER, ADMIN) create, change, archive and unarchive. Nothing is ever deleted.
 * Subcategories: one level only; an archived parent hides its children from pickers.
 */
@Service
public class CategoryService {

	/** Usage counts look at transactions dated in the last this many days. */
	public static final int USAGE_DAYS = 90;

	/** The seeded "Other" has this sort order; new categories get one just below it. */
	static final int LAST_SORT_ORDER = 1000;

	private static final int SORT_STEP = 10;

	private final CategoryRepository categories;

	private final Actors actors;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public CategoryService(CategoryRepository categories, Actors actors, JdbcTemplate jdbc, Clock clock) {
		this.categories = categories;
		this.actors = actors;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Picker: active categories whose parent (if any) is active, most used in the last
	 * {@value #USAGE_DAYS} days first, then by name.
	 */
	@Transactional(readOnly = true)
	public List<CategoryPick> active(AuthUser principal) {
		this.actors.active(principal);
		List<Category> all = this.categories.findAll();
		Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
		Map<UUID, Long> usage = usage();
		return all.stream()
			.filter((category) -> isPickable(category, byId))
			.map((category) -> new CategoryPick(category.getId(), category.getName(), category.getIcon(), false,
					category.getParentId(), usage.getOrDefault(category.getId(), 0L)))
			.sorted(Comparator.comparingLong(CategoryPick::usageCount)
				.reversed()
				.thenComparing(CategoryPick::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	/** All categories, archived too (managers only), by name. */
	@Transactional(readOnly = true)
	public List<CategoryResponse> all(AuthUser principal) {
		this.actors.manager(principal);
		return this.categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
	}

	/**
	 * @param name already trimmed and checked (1 to 40 characters)
	 * @throws ApiException 409 {@code name_taken}, 400 {@code validation_error} (icon,
	 * parent)
	 */
	@Transactional
	public CategoryResponse create(AuthUser principal, String name, String icon, UUID parentId) {
		this.actors.manager(principal);
		CategoryIcons.check(icon);
		CategoryTree.checkParent(null, parentId, false, this::node);
		if (this.categories.findByNameIgnoreCase(name).isPresent()) {
			throw nameTaken();
		}
		Integer max = this.categories.maxSortOrderBelow(LAST_SORT_ORDER);
		int sortOrder = Math.min(((max != null) ? max : 0) + SORT_STEP, LAST_SORT_ORDER - 1);
		Category category = Category.create(name, icon, parentId, sortOrder, this.clock.instant());
		return CategoryResponse.from(saveFlush(category));
	}

	/**
	 * Changes the name, the icon and/or the parent (null or not given = keep).
	 * @throws ApiException 404 {@code category_not_found}, 409 {@code name_taken}, 400
	 * {@code validation_error}
	 */
	@Transactional
	public CategoryResponse update(AuthUser principal, UUID id, String name, String icon, ParentPatch parent) {
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
			CategoryIcons.check(icon);
			category.changeIcon(icon, this.clock.instant());
		}
		if (parent.given()) {
			CategoryTree.checkParent(id, parent.parentId(), this.categories.existsByParentId(id), this::node);
			category.moveTo(parent.parentId(), this.clock.instant());
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

	/**
	 * True if a category can be picked: active, and its parent (if any) is active.
	 */
	public boolean isPickable(Category category) {
		Category parent = (category.getParentId() != null) ? this.categories.findById(category.getParentId()).orElse(null)
				: null;
		return !category.isArchived() && (parent == null || !parent.isArchived());
	}

	private static boolean isPickable(Category category, Map<UUID, Category> byId) {
		Category parent = (category.getParentId() != null) ? byId.get(category.getParentId()) : null;
		return !category.isArchived() && (parent == null || !parent.isArchived());
	}

	/** Non-trashed company transactions per category, dated in the last 90 days. */
	private Map<UUID, Long> usage() {
		Map<UUID, Long> usage = new HashMap<>();
		LocalDate since = LocalDate.now(this.clock.withZone(ZoneOffset.UTC)).minusDays(USAGE_DAYS);
		this.jdbc.query("SELECT category_id, count(*) AS n FROM transactions WHERE deleted_at IS NULL AND date >= ? "
				+ "GROUP BY category_id", (rs) -> {
					usage.put(rs.getObject("category_id", UUID.class), rs.getLong("n"));
				}, Date.valueOf(since));
		return usage;
	}

	private Optional<CategoryTree.Node> node(UUID id) {
		return this.categories.findById(id)
			.map((category) -> new CategoryTree.Node(category.getId(), category.getParentId(), category.isArchived()));
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

	private static ApiException nameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "name_taken", "A category with this name already exists");
	}

}
