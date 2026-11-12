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
import com.nordencapital.spine.common.CategoryKinds;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.logo.LogoService;
import com.nordencapital.spine.common.CategoryTree;
import com.nordencapital.spine.common.ParentPatch;
import com.nordencapital.spine.user.Actors;

/**
 * Expense categories. Everyone reads the active ones (most used first); only managers
 * (OWNER) creates, changes, archives and unarchives. Nothing is ever deleted.
 * Subcategories: one level only; an archived parent hides its children from pickers.
 */
@Service
public class CategoryService {

	/** Usage counts look at transactions dated in the last this many days. */
	public static final int USAGE_DAYS = 90;

	private final CategoryRepository categories;

	private final Actors actors;

	private final JdbcTemplate jdbc;

	private final LogoService logos;

	private final Clock clock;

	public CategoryService(CategoryRepository categories, Actors actors, JdbcTemplate jdbc, LogoService logos,
			Clock clock) {
		this.categories = categories;
		this.actors = actors;
		this.jdbc = jdbc;
		this.logos = logos;
		this.clock = clock;
	}

	/**
	 * Picker: active categories whose parent (if any) is active, most used in the last
	 * {@value #USAGE_DAYS} days first, then by name.
	 */
	@Transactional(readOnly = true)
	public List<CategoryPick> active(AuthUser principal, String kind) {
		this.actors.active(principal);
		String onlyKind = CategoryKinds.parse("kind", kind);
		List<Category> all = this.categories.findAll();
		Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
		Map<UUID, Long> usage = usage();
		Map<String, List<String>> logoUrls = logoUrls(all);
		return all.stream()
			.filter((category) -> isPickable(category, byId))
			.filter((category) -> onlyKind == null || onlyKind.equals(category.getKind()))
			.map((category) -> new CategoryPick(category.getId(), category.getName(), category.getIcon(),
					category.getKind(), false,
					category.getParentId(), category.getPosition(), category.getWebsite(), LogoService.chain(logoUrls, category.getWebsite()),
					usage.getOrDefault(category.getId(), 0L)))
			.sorted(Comparator.comparingLong(CategoryPick::usageCount)
				.reversed()
				.thenComparing(CategoryPick::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	/**
	 * All categories, archived too (managers only), in manage-page order: each main
	 * category by position, followed by its subcategories by position.
	 */
	@Transactional(readOnly = true)
	public List<CategoryResponse> all(AuthUser principal, String kind) {
		this.actors.owner(principal);
		return allOrdered(CategoryKinds.parse("kind", kind));
	}

	private List<CategoryResponse> allOrdered(String onlyKind) {
		List<Category> all = CategoryTree
			.ordered(this.categories.findAll(), Category::getId, Category::getParentId, Category::getPosition,
					Category::getName)
			.stream()
			.filter((category) -> onlyKind == null || onlyKind.equals(category.getKind()))
			.toList();
		Map<String, List<String>> logoUrls = logoUrls(all);
		return all.stream().map((category) -> CategoryResponse.from(category, logoUrls)).toList();
	}

	/**
	 * @param name already trimmed and checked (1 to 40 characters)
	 * @throws ApiException 409 {@code name_taken}, 400 {@code validation_error} (icon,
	 * parent)
	 */
	@Transactional
	public CategoryResponse create(AuthUser principal, String name, String icon, UUID parentId, String website,
			String kind) {
		this.actors.owner(principal);
		CategoryIcons.check(icon);
		String domain = Websites.normalize(website);
		String wanted = CategoryKinds.parse("kind", kind);
		CategoryTree.checkParent(null, parentId, false, this::node);
		String finalKind = (wanted != null) ? wanted : CategoryKinds.EXPENSE;
		if (parentId != null) {
			finalKind = find(parentId).getKind();
			if (wanted != null && !wanted.equals(finalKind)) {
				throw CategoryKinds.subKind();
			}
		}
		if (this.categories.findByNameIgnoreCase(name).isPresent()) {
			throw nameTaken();
		}
		Category category = Category.create(name, icon, parentId, endOf(parentId, null), finalKind,
				this.clock.instant());
		if (domain != null) {
			category.changeWebsite(domain, this.clock.instant());
		}
		return response(saveFlush(category));
	}

	/**
	 * Changes the name, the icon and/or the parent (null or not given = keep).
	 * @throws ApiException 404 {@code category_not_found}, 409 {@code name_taken}, 400
	 * {@code validation_error}
	 */
	@Transactional
	public CategoryResponse update(AuthUser principal, UUID id, String name, String icon, ParentPatch parent,
			Websites.Patch website, String kind) {
		this.actors.owner(principal);
		Category category = find(id);
		String wanted = CategoryKinds.parse("kind", kind);
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
			if (parent.parentId() != null) {
				CategoryKinds.checkParent(find(parent.parentId()).getKind(),
						(wanted != null) ? wanted : category.getKind());
			}
			if (!java.util.Objects.equals(parent.parentId(), category.getParentId())) {
				UUID oldParent = category.getParentId();
				category.setPosition(endOf(parent.parentId(), id));
				category.moveTo(parent.parentId(), this.clock.instant());
				renumber(oldParent, id);
			}
		}
		if (website.given() && !java.util.Objects.equals(website.domain(), category.getWebsite())) {
			category.changeWebsite(website.domain(), this.clock.instant());
		}
		if (wanted != null && !wanted.equals(category.getKind())) {
			if (category.getParentId() != null) {
				throw CategoryKinds.subKind();
			}
			// A main category changes kind: its subcategories follow. Old transactions keep it.
			category.changeKind(wanted, this.clock.instant());
			this.categories.findAll()
				.stream()
				.filter((c) -> id.equals(c.getParentId()))
				.forEach((c) -> c.changeKind(wanted, this.clock.instant()));
		}
		return response(saveFlush(category));
	}

	/**
	 * Drag and drop: puts the category under {@code parentId} (null = main category) at
	 * {@code position} (0-based among its new siblings; too big = last) and renumbers the
	 * siblings. Archived categories can be moved too. Not written to any history.
	 * @return all categories in manage-page order
	 * @throws ApiException 404 {@code category_not_found}, 400 {@code validation_error}
	 * (own parent, under a subcategory, or it has subcategories)
	 */
	@Transactional
	public List<CategoryResponse> move(AuthUser principal, UUID id, UUID parentId, int position) {
		this.actors.owner(principal);
		this.jdbc.queryForList("SELECT id FROM categories FOR UPDATE", UUID.class);
		Category category = find(id);
		CategoryTree.checkParent(id, parentId, this.categories.existsByParentId(id), true, this::node);
		if (parentId != null) {
			CategoryKinds.checkParent(find(parentId).getKind(), category.getKind());
		}
		UUID oldParent = category.getParentId();
		List<Category> all = this.categories.findAll();
		List<UUID> order = CategoryTree.insertAt(siblingIds(all, parentId, id), id, position);
		Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
		for (int i = 0; i < order.size(); i++) {
			byId.get(order.get(i)).setPosition(i);
		}
		if (!java.util.Objects.equals(oldParent, parentId)) {
			category.moveTo(parentId, this.clock.instant());
			List<UUID> old = siblingIds(all, oldParent, id);
			for (int i = 0; i < old.size(); i++) {
				byId.get(old.get(i)).setPosition(i);
			}
		}
		this.categories.flush();
		return allOrdered(null);
	}

	/** Ids of the categories under {@code parentId} (without {@code except}), in order. */
	private static List<UUID> siblingIds(List<Category> all, UUID parentId, UUID except) {
		return all.stream()
			.filter((c) -> java.util.Objects.equals(c.getParentId(), parentId) && !c.getId().equals(except))
			.sorted(Comparator.comparingInt(Category::getPosition)
				.thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
			.map(Category::getId)
			.toList();
	}

	/** Position after the last sibling under {@code parentId}. */
	private int endOf(UUID parentId, UUID except) {
		return this.categories.findAll()
			.stream()
			.filter((c) -> java.util.Objects.equals(c.getParentId(), parentId) && !c.getId().equals(except))
			.mapToInt((c) -> c.getPosition() + 1)
			.max()
			.orElse(0);
	}

	/** Closes the gap left in the old group after a category moved out of it. */
	private void renumber(UUID parentId, UUID movedId) {
		List<Category> all = this.categories.findAll();
		Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
		List<UUID> ids = siblingIds(all, parentId, movedId);
		for (int i = 0; i < ids.size(); i++) {
			byId.get(ids.get(i)).setPosition(i);
		}
	}

	/**
	 * Archives or unarchives (no change if already in that state).
	 * @throws ApiException 404 {@code category_not_found}
	 */
	@Transactional
	public CategoryResponse setArchived(AuthUser principal, UUID id, boolean archived) {
		this.actors.owner(principal);
		Category category = find(id);
		if (category.isArchived() != archived) {
			category.setArchived(archived, this.clock.instant());
		}
		return response(category);
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

	/** Response of one saved category; starts fetching its logo in the background. */
	private CategoryResponse response(Category category) {
		this.logos.prefetch(category.getWebsite());
		return CategoryResponse.from(category, logoUrls(List.of(category)));
	}

	private Map<String, List<String>> logoUrls(List<Category> categories) {
		return this.logos.logoUrls(categories.stream().map(Category::getWebsite).toList());
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
