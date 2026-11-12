package com.nordencapital.spine.wallet;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.DuplicateException;
import com.nordencapital.spine.common.ListFilter;
import com.nordencapital.spine.common.ListPage;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.common.TransactionSearch;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.file.FileResponse;
import com.nordencapital.spine.file.FileService;
import com.nordencapital.spine.file.StoredFile;
import com.nordencapital.spine.file.StoredFileRepository;
import com.nordencapital.spine.logo.LogoService;
import com.nordencapital.spine.me.UserZones;
import com.nordencapital.spine.transaction.TransactionRequest;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.transaction.TransactionResponse.TagRef;
import com.nordencapital.spine.user.User;

/**
 * My private wallet transactions. Every query is limited to the owner. Files are
 * optional (0 to 5). Delete is a hard delete (private, no trash): its files go too.
 */
@Component
public class WalletTransactions {

	static final int MAX_TAGS = 10;

	static final int MAX_FILES = 5;

	private static final ZoneId LATEST_ZONE = ZoneOffset.ofHours(14);

	private static final TransactionSearch SEARCH = new TransactionSearch("wallet_transactions",
			"wallet_transaction_tags", "wallet_categories", null);

	private static final String COLUMNS = "id, type, amount, currency, date, description, website, category_id, recurring_id, "
			+ "created_at, updated_at";

	private final JdbcTemplate jdbc;

	private final WalletCategories categories;

	private final WalletTags tags;

	private final StoredFileRepository files;

	private final FileService fileService;

	private final WalletBudgets budgets;

	private final UserZones zones;

	private final LogoService logos;

	private final Clock clock;

	public WalletTransactions(JdbcTemplate jdbc, WalletCategories categories, WalletTags tags,
			StoredFileRepository files, FileService fileService, WalletBudgets budgets, UserZones zones,
			LogoService logos, Clock clock) {
		this.jdbc = jdbc;
		this.categories = categories;
		this.tags = tags;
		this.files = files;
		this.fileService = fileService;
		this.budgets = budgets;
		this.zones = zones;
		this.logos = logos;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ListPage<WalletTx> list(User user, ListFilter.Params params) {
		ZoneId zone = this.zones.saved(user.getId()).orElse(ZoneOffset.UTC);
		ListFilter filter = ListFilter.parse(params, YearMonth.now(this.clock.withZone(zone)));
		TransactionSearch.Result result = SEARCH.run(this.jdbc, filter, "t.user_id = ?", List.of(user.getId()), null);
		return new ListPage<>(load(user.getId(), result.ids()), result.totals(), filter.page(), filter.size(),
				result.count(), result.hasNext(filter));
	}

	/** @throws ApiException 404 {@code transaction_not_found} */
	@Transactional(readOnly = true)
	public WalletTx get(User user, UUID id) {
		return load(user.getId(), List.of(id)).stream().findFirst().orElseThrow(WalletTransactions::notFound);
	}

	/** My newest transactions. */
	@Transactional(readOnly = true)
	public List<WalletTx> recent(UUID userId, int count) {
		return load(userId, this.jdbc.queryForList("SELECT id FROM wallet_transactions WHERE user_id = ? "
				+ "ORDER BY date DESC, created_at DESC, id LIMIT ?", UUID.class, userId, count));
	}

	/**
	 * @throws ApiException 400 {@code validation_error} / {@code future_date}
	 * @throws DuplicateException (409) one of my transactions has the same type, amount,
	 * currency and date, and confirmDuplicate is not true
	 */
	@Transactional
	public WalletTx create(User user, TransactionRequest request) {
		Checked checked = check(user, request, null);
		if (!Boolean.TRUE.equals(request.confirmDuplicate())) {
			List<DuplicateException.Match> matches = duplicates(user, checked);
			if (!matches.isEmpty()) {
				throw new DuplicateException(matches);
			}
		}
		UUID id = UUID.randomUUID();
		this.jdbc.update("INSERT INTO wallet_transactions (id, user_id, type, amount, currency, date, description, "
				+ "website, category_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, user.getId(),
				checked.type(), checked.amount(), checked.currency(), Date.valueOf(checked.date()),
				checked.description(), checked.website(), checked.categoryId(), Timestamp.from(this.clock.instant()));
		saveTags(id, checked.tagIds());
		checked.files().forEach((file) -> attach(file, id));
		this.budgets.checkAlerts(user.getId());
		this.logos.prefetch(checked.website());
		return get(user, id);
	}

	/**
	 * Full replace (no duplicate check). Files left out are deleted.
	 * @throws ApiException 404 {@code transaction_not_found}, 400
	 */
	@Transactional
	public WalletTx update(User user, UUID id, TransactionRequest request) {
		WalletTx existing = get(user, id);
		Checked checked = check(user, request, existing);
		this.jdbc.update("UPDATE wallet_transactions SET type = ?, amount = ?, currency = ?, date = ?, description = ?, "
				+ "website = ?, category_id = ?, updated_at = ? WHERE id = ? AND user_id = ?", checked.type(),
				checked.amount(), checked.currency(), Date.valueOf(checked.date()), checked.description(),
				checked.website(), checked.categoryId(), Timestamp.from(this.clock.instant()), id, user.getId());
		this.jdbc.update("DELETE FROM wallet_transaction_tags WHERE transaction_id = ?", id);
		saveTags(id, checked.tagIds());
		Set<UUID> keep = checked.files().stream().map(StoredFile::getId).collect(Collectors.toSet());
		List<StoredFile> current = this.files.findAllByWalletTransactionIdInOrderByCreatedAtAsc(List.of(id));
		this.fileService.deleteNow(current.stream().filter((file) -> !keep.contains(file.getId())).toList());
		checked.files().forEach((file) -> attach(file, id));
		this.budgets.checkAlerts(user.getId());
		this.logos.prefetch(checked.website());
		return get(user, id);
	}

	/**
	 * Hard delete, with its files.
	 * @throws ApiException 404 {@code transaction_not_found}
	 */
	@Transactional
	public void delete(User user, UUID id) {
		get(user, id);
		this.fileService.deleteNow(this.files.findAllByWalletTransactionIdInOrderByCreatedAtAsc(List.of(id)));
		this.jdbc.update("DELETE FROM wallet_transactions WHERE id = ? AND user_id = ?", id, user.getId());
	}

	/** INCOME - EXPENSE of all my transactions, per currency. */
	@Transactional(readOnly = true)
	public Map<String, String> balance(UUID userId) {
		Map<String, BigDecimal> sums = sums(userId, null, null);
		Map<String, String> balance = new java.util.LinkedHashMap<>();
		Totals.perCurrency(sums).forEach((currency, totals) -> balance.put(currency, totals.net()));
		return balance;
	}

	/** In/out/net per currency between two dates (included). */
	@Transactional(readOnly = true)
	public Map<String, Totals> totals(UUID userId, LocalDate from, LocalDate to) {
		return Totals.perCurrency(sums(userId, from, to));
	}

	private Map<String, BigDecimal> sums(UUID userId, LocalDate from, LocalDate to) {
		List<Object> args = new ArrayList<>(List.of(userId));
		String where = "user_id = ?";
		if (from != null) {
			where += " AND date >= ? AND date <= ?";
			args.add(Date.valueOf(from));
			args.add(Date.valueOf(to));
		}
		Map<String, BigDecimal> sums = new HashMap<>();
		this.jdbc.query("SELECT currency, type, sum(amount) AS total FROM wallet_transactions WHERE " + where
				+ " GROUP BY currency, type", (rs) -> {
					sums.put(rs.getString("currency") + ":" + rs.getString("type"), rs.getBigDecimal("total"));
				}, args.toArray());
		return sums;
	}

	// ---- checks ----

	private Checked check(User user, TransactionRequest request, WalletTx existing) {
		BigDecimal amount = Money.parse("amount", request.amount());
		LocalDate date;
		try {
			date = LocalDate.parse(request.date());
		}
		catch (DateTimeParseException ex) {
			throw invalid("date: must be a real date like 2026-03-31");
		}
		String website = Websites.normalize(request.website());
		ZoneId zone = this.zones.saved(user.getId()).orElse(LATEST_ZONE);
		if (date.isAfter(LocalDate.now(this.clock.withZone(zone)))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "future_date", "The date cannot be in the future");
		}

		WalletCategories.Category category = this.categories.find(user.getId(), request.categoryId())
			.orElseThrow(() -> invalid("categoryId: unknown category"));
		boolean keepsOld = existing != null && existing.category() != null
				&& existing.category().id().equals(category.id());
		if (!keepsOld && !this.categories.isPickable(user.getId(), category)) {
			throw invalid("categoryId: this category (or its parent) is archived");
		}

		Set<UUID> tagIds = distinct(request.tagIds());
		if (tagIds.size() > MAX_TAGS) {
			throw invalid("tagIds: at most " + MAX_TAGS + " tags");
		}
		Set<UUID> oldTags = (existing != null) ? existing.tags().stream().map(TagRef::id).collect(Collectors.toSet())
				: Set.of();
		List<WalletTags.Tag> foundTags = this.tags.mine(user.getId(), tagIds);
		if (foundTags.size() != tagIds.size()) {
			throw invalid("tagIds: unknown tag");
		}
		if (foundTags.stream().anyMatch((tag) -> tag.archived() && !oldTags.contains(tag.id()))) {
			throw invalid("tagIds: a tag is archived");
		}

		Set<UUID> fileIds = distinct(request.fileIds());
		if (fileIds.size() > MAX_FILES) {
			throw invalid("fileIds: at most " + MAX_FILES + " files");
		}
		List<StoredFile> foundFiles = new ArrayList<>(this.files.findAllById(fileIds));
		UUID existingId = (existing != null) ? existing.id() : null;
		boolean usable = foundFiles.size() == fileIds.size()
				&& foundFiles.stream().allMatch((file) -> file.getOwnerId().equals(user.getId())
						&& (file.isUnattached() || (existingId != null && existingId.equals(file.getWalletTransactionId()))));
		if (!usable) {
			throw invalid("fileIds: use files you just uploaded, or files already on this transaction");
		}
		foundFiles.sort(Comparator.comparing(StoredFile::getCreatedAt));
		return new Checked(request.type(), amount, request.currency(), date, request.description(), website,
				category.id(), tagIds, foundFiles);
	}

	private List<DuplicateException.Match> duplicates(User user, Checked checked) {
		DuplicateException.UserRef me = new DuplicateException.UserRef(user.getId(), user.displayName());
		return this.jdbc.query(
				"SELECT id, description, created_at FROM wallet_transactions WHERE user_id = ? AND type = ? AND amount = ? "
						+ "AND currency = ? AND date = ? ORDER BY created_at DESC LIMIT 5",
				(rs, row) -> new DuplicateException.Match(rs.getObject("id", UUID.class), rs.getString("description"), me,
						rs.getTimestamp("created_at").toInstant()),
				user.getId(), checked.type(), checked.amount(), checked.currency(), Date.valueOf(checked.date()));
	}

	private void saveTags(UUID transactionId, Set<UUID> tagIds) {
		for (UUID tagId : tagIds) {
			this.jdbc.update("INSERT INTO wallet_transaction_tags (transaction_id, tag_id) VALUES (?, ?)", transactionId,
					tagId);
		}
	}

	/** Through the entity, so the loaded file objects stay right (the row exists already). */
	private void attach(StoredFile file, UUID walletTransactionId) {
		file.attachToWallet(walletTransactionId);
	}

	// ---- reading ----

	/** My transactions with these ids, in the given order. */
	private List<WalletTx> load(UUID userId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		UUID[] idArray = ids.toArray(new UUID[0]);
		Map<UUID, Row> rows = this.jdbc
			.query("SELECT " + COLUMNS + " FROM wallet_transactions WHERE user_id = ? AND id = ANY (?)",
					WalletTransactions::map, userId, idArray)
			.stream()
			.collect(Collectors.toMap(Row::id, (row) -> row));
		Map<UUID, List<UUID>> tagLinks = new HashMap<>();
		this.jdbc.query("SELECT transaction_id, tag_id FROM wallet_transaction_tags WHERE transaction_id = ANY (?)",
				(rs) -> {
					tagLinks.computeIfAbsent(rs.getObject("transaction_id", UUID.class), (k) -> new ArrayList<>())
						.add(rs.getObject("tag_id", UUID.class));
				}, (Object) idArray);
		Map<UUID, WalletTags.Tag> tagsById = this.tags
			.mine(userId, tagLinks.values().stream().flatMap(List::stream).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(WalletTags.Tag::id, (tag) -> tag));
		Map<UUID, WalletCategories.Category> categoriesById = this.categories.withParents(userId,
				rows.values().stream().map(Row::categoryId).collect(Collectors.toSet()));
		Map<UUID, List<FileResponse>> filesByTx = this.files.findAllByWalletTransactionIdInOrderByCreatedAtAsc(ids)
			.stream()
			.collect(Collectors.groupingBy(StoredFile::getWalletTransactionId,
					Collectors.mapping(FileResponse::from, Collectors.toList())));
		List<String> domains = new ArrayList<>();
		rows.values().stream().map(Row::website).filter(Objects::nonNull).forEach(domains::add);
		categoriesById.values().stream().map(WalletCategories.Category::website).filter(Objects::nonNull)
			.forEach(domains::add);
		Map<String, String> logoUrls = this.logos.logoUrls(domains);
		List<WalletTx> result = new ArrayList<>();
		for (UUID id : ids) {
			Row row = rows.get(id);
			if (row == null) {
				continue;
			}
			WalletCategories.Category category = categoriesById.get(row.categoryId());
			WalletCategories.Category parent = (category != null && category.parentId() != null)
					? categoriesById.get(category.parentId()) : null;
			String logoUrl = LogoService.firstLogo(logoUrls, row.website(),
					(category != null) ? category.website() : null, (parent != null) ? parent.website() : null);
			CategoryRef categoryRef = (category != null) ? new CategoryRef(category.id(), category.name(),
					category.icon(), category.archived(), category.parentId()) : null;
			List<TagRef> tagRefs = tagLinks.getOrDefault(id, List.of())
				.stream()
				.map(tagsById::get)
				.filter(Objects::nonNull)
				.sorted(Comparator.comparing(WalletTags.Tag::name, String.CASE_INSENSITIVE_ORDER))
				.map((tag) -> new TagRef(tag.id(), tag.name()))
				.toList();
			result.add(new WalletTx(id, row.type(), Totals.money(row.amount()), row.currency(), row.date(),
					row.description(), row.website(), logoUrl, categoryRef, tagRefs, filesByTx.getOrDefault(id, List.of()), row.recurringId(),
					row.createdAt(), row.updatedAt()));
		}
		return result;
	}

	private static Row map(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getBigDecimal("amount"),
				rs.getString("currency"), rs.getDate("date").toLocalDate(), rs.getString("description"),
				rs.getString("website"), rs.getObject("category_id", UUID.class), rs.getObject("recurring_id", UUID.class),
				rs.getTimestamp("created_at").toInstant(),
				(rs.getTimestamp("updated_at") != null) ? rs.getTimestamp("updated_at").toInstant() : null);
	}

	private static Set<UUID> distinct(Collection<UUID> ids) {
		return (ids != null) ? ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new))
				: Set.of();
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found", "Transaction not found");
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	private record Row(UUID id, String type, BigDecimal amount, String currency, LocalDate date, String description,
			String website, UUID categoryId, UUID recurringId, Instant createdAt, Instant updatedAt) {
	}

	private record Checked(String type, BigDecimal amount, String currency, LocalDate date, String description,
			String website, UUID categoryId, Set<UUID> tagIds, List<StoredFile> files) {
	}

	/**
	 * A wallet transaction as the API returns it.
	 * @param website bare domain like "claude.ai", or null
	 * @param logoUrl path of the website's logo, or null (show the category icon)
	 * @param recurringId the recurring item that made it (null if added by hand)
	 * @param updatedAt null if never changed
	 */
	public record WalletTx(UUID id, String type, String amount, String currency, LocalDate date, String description,
			String website, String logoUrl, CategoryRef category, List<TagRef> tags, List<FileResponse> files, UUID recurringId, Instant createdAt,
			Instant updatedAt) {
	}

}
