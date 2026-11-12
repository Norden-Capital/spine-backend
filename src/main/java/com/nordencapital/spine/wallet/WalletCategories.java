package com.nordencapital.spine.wallet;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.category.CategoryIcons;
import com.nordencapital.spine.category.CategoryService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.logo.LogoService;
import com.nordencapital.spine.common.CategoryTree;
import com.nordencapital.spine.common.ParentPatch;

/**
 * My private wallet categories (one level of subcategories). Only the owner ever sees
 * them; other users get "not found". Nothing is deleted, only archived.
 */
@Component
public class WalletCategories {

	/** Starting categories, made when the PIN is first set. */
	static final List<String[]> SEEDS = List.of(new String[] { "Food", "utensils" },
			new String[] { "Transport", "car" }, new String[] { "Bills", "receipt" },
			new String[] { "Shopping", "shopping-cart" }, new String[] { "Health", "heart-pulse" },
			new String[] { "Fun", "ticket" }, new String[] { "Salary", "banknote" },
			new String[] { "Other", "ellipsis" });

	private static final String COLUMNS = "id, parent_id, name, icon, archived, website";

	private final JdbcTemplate jdbc;

	private final LogoService logos;

	private final Clock clock;

	public WalletCategories(JdbcTemplate jdbc, LogoService logos, Clock clock) {
		this.jdbc = jdbc;
		this.logos = logos;
		this.clock = clock;
	}

	/** Makes the starting categories (only if I have none). */
	@Transactional
	public void seed(UUID userId) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM wallet_categories WHERE user_id = ?",
				Integer.class, userId);
		if (count != null && count > 0) {
			return;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		for (String[] seed : SEEDS) {
			this.jdbc.update("INSERT INTO wallet_categories (id, user_id, name, icon, archived, created_at, updated_at) "
					+ "VALUES (?, ?, ?, ?, false, ?, ?)", UUID.randomUUID(), userId, seed[0], seed[1], now, now);
		}
	}

	/**
	 * Picker: active (and parent active), most used in my wallet in the last 90 days,
	 * then by name.
	 */
	@Transactional(readOnly = true)
	public List<Pick> picker(UUID userId) {
		List<Category> all = all(userId);
		Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::id, Function.identity()));
		Map<UUID, Long> usage = usage(userId);
		Map<String, String> logoUrls = logoUrls(all);
		return all.stream()
			.filter((category) -> pickable(category, byId))
			.map((category) -> category.pick(usage.getOrDefault(category.id(), 0L), logoUrls))
			.sorted(Comparator.comparingLong(Pick::usageCount).reversed()
				.thenComparing(Pick::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	/** All my categories, archived too, by name, with usage. */
	@Transactional(readOnly = true)
	public List<Pick> allPicks(UUID userId) {
		Map<UUID, Long> usage = usage(userId);
		List<Category> all = all(userId);
		Map<String, String> logoUrls = logoUrls(all);
		return all.stream()
			.map((category) -> category.pick(usage.getOrDefault(category.id(), 0L), logoUrls))
			.sorted(Comparator.comparing(Pick::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	/**
	 * @throws ApiException 409 {@code name_taken}, 400 {@code validation_error}
	 */
	@Transactional
	public Pick create(UUID userId, String name, String icon, UUID parentId, String website) {
		CategoryIcons.check(icon);
		String domain = Websites.normalize(website);
		CategoryTree.checkParent(null, parentId, false, (id) -> node(userId, id));
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		try {
			this.jdbc.update("INSERT INTO wallet_categories (id, user_id, parent_id, name, icon, website, archived, "
					+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, false, ?, ?)", id, userId, parentId, name, icon,
					domain, now, now);
		}
		catch (DuplicateKeyException ex) {
			throw nameTaken();
		}
		return picked(userId, id, 0L);
	}

	/**
	 * @throws ApiException 404 {@code category_not_found}, 409 {@code name_taken}, 400
	 * {@code validation_error}
	 */
	@Transactional
	public Pick update(UUID userId, UUID id, String name, String icon, ParentPatch parent, Websites.Patch website) {
		Category category = mine(userId, id);
		if (icon != null) {
			CategoryIcons.check(icon);
		}
		UUID parentId = category.parentId();
		if (parent.given()) {
			boolean hasChildren = Boolean.TRUE.equals(this.jdbc.queryForObject(
					"SELECT EXISTS (SELECT 1 FROM wallet_categories WHERE parent_id = ?)", Boolean.class, id));
			CategoryTree.checkParent(id, parent.parentId(), hasChildren, (pid) -> node(userId, pid));
			parentId = parent.parentId();
		}
		try {
			this.jdbc.update("UPDATE wallet_categories SET name = ?, icon = ?, parent_id = ?, website = ?, updated_at = ? "
					+ "WHERE id = ?", (name != null) ? name : category.name(), (icon != null) ? icon : category.icon(),
					parentId, website.given() ? website.domain() : category.website(),
					Timestamp.from(this.clock.instant()), id);
		}
		catch (DuplicateKeyException ex) {
			throw nameTaken();
		}
		return picked(userId, id, usage(userId).getOrDefault(id, 0L));
	}

	/** @throws ApiException 404 {@code category_not_found} */
	@Transactional
	public Pick setArchived(UUID userId, UUID id, boolean archived) {
		mine(userId, id);
		this.jdbc.update("UPDATE wallet_categories SET archived = ?, updated_at = ? WHERE id = ?", archived,
				Timestamp.from(this.clock.instant()), id);
		return picked(userId, id, usage(userId).getOrDefault(id, 0L));
	}

	/** One saved category as a Pick; starts fetching its logo in the background. */
	private Pick picked(UUID userId, UUID id, long usageCount) {
		Category category = mine(userId, id);
		this.logos.prefetch(category.website());
		return category.pick(usageCount, logoUrls(List.of(category)));
	}

	private Map<String, String> logoUrls(List<Category> categories) {
		return this.logos.logoUrls(categories.stream().map(Category::website).toList());
	}

	/** One of my categories. @throws ApiException 404 {@code category_not_found} */
	Category mine(UUID userId, UUID id) {
		return find(userId, id)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "category_not_found", "Category not found"));
	}

	Optional<Category> find(UUID userId, UUID id) {
		return this.jdbc
			.query("SELECT " + COLUMNS + " FROM wallet_categories WHERE id = ? AND user_id = ?",
					WalletCategories::map, id, userId)
			.stream()
			.findFirst();
	}

	/** True if it can be used on a new transaction: active, and its parent is active. */
	boolean isPickable(UUID userId, Category category) {
		Category parent = (category.parentId() != null) ? find(userId, category.parentId()).orElse(null) : null;
		return !category.archived() && (parent == null || !parent.archived());
	}

	Map<UUID, Category> byIds(UUID userId, java.util.Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return all(userId).stream()
			.filter((category) -> ids.contains(category.id()))
			.collect(Collectors.toMap(Category::id, Function.identity()));
	}

	/** These categories of mine plus their parents. */
	Map<UUID, Category> withParents(UUID userId, java.util.Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Category> all = all(userId).stream().collect(Collectors.toMap(Category::id, Function.identity()));
		Map<UUID, Category> result = new HashMap<>();
		for (UUID id : ids) {
			Category category = all.get(id);
			if (category != null) {
				result.put(id, category);
				if (category.parentId() != null && all.containsKey(category.parentId())) {
					result.put(category.parentId(), all.get(category.parentId()));
				}
			}
		}
		return result;
	}

	private List<Category> all(UUID userId) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM wallet_categories WHERE user_id = ?",
				WalletCategories::map, userId);
	}

	private Map<UUID, Long> usage(UUID userId) {
		Map<UUID, Long> usage = new HashMap<>();
		LocalDate since = LocalDate.now(this.clock.withZone(ZoneOffset.UTC)).minusDays(CategoryService.USAGE_DAYS);
		this.jdbc.query("SELECT category_id, count(*) AS n FROM wallet_transactions WHERE user_id = ? AND date >= ? "
				+ "GROUP BY category_id", (rs) -> {
					usage.put(rs.getObject("category_id", UUID.class), rs.getLong("n"));
				}, userId, Date.valueOf(since));
		return usage;
	}

	private Optional<CategoryTree.Node> node(UUID userId, UUID id) {
		return find(userId, id).map((category) -> new CategoryTree.Node(category.id(), category.parentId(),
				category.archived()));
	}

	private static boolean pickable(Category category, Map<UUID, Category> byId) {
		Category parent = (category.parentId() != null) ? byId.get(category.parentId()) : null;
		return !category.archived() && (parent == null || !parent.archived());
	}

	private static Category map(ResultSet rs, int row) throws SQLException {
		return new Category(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
				rs.getString("name"), rs.getString("icon"), rs.getBoolean("archived"), rs.getString("website"));
	}

	private static ApiException nameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "name_taken", "You already have a category with this name");
	}

	record Category(UUID id, UUID parentId, String name, String icon, boolean archived, String website) {

		Pick pick(long usageCount, Map<String, String> logoUrls) {
			return new Pick(this.id, this.name, this.icon, this.archived, this.parentId, this.website,
					logoUrls.get(this.website), usageCount);
		}

	}

	/**
	 * A wallet category as the API returns it.
	 * @param website bare domain, or null
	 * @param logoUrl path of the website's logo, or null (show the icon)
	 */
	public record Pick(UUID id, String name, String icon, boolean archived, UUID parentId, String website,
			String logoUrl, long usageCount) {
	}

}
