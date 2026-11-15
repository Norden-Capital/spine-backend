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
import com.nordencapital.spine.common.CategoryKinds;
import com.nordencapital.spine.common.DuplicateException;
import com.nordencapital.spine.common.ListFilter;
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
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.user.User;

/**
 * My private wallet transactions. Every query is limited to the owner. Files are
 * optional (0 to 5). Delete is a hard delete (private, no trash): its files go too.
 */
@Component
public class WalletTransactions {

	static final int MAX_FILES = 5;

	private static final ZoneId LATEST_ZONE = ZoneOffset.ofHours(14);

	private static final TransactionSearch SEARCH = new TransactionSearch("wallet_transactions",
			"wallet_categories", null);

	private static final String COLUMNS = "id, type, amount, currency, date, description, website, category_id, recurring_id, "
			+ "created_at, updated_at";

	private final JdbcTemplate jdbc;

	private final WalletCategories categories;

	private final StoredFileRepository files;

	private final FileService fileService;

	private final WalletBudgets budgets;

	private final UserZones zones;

	private final LogoService logos;

	private final WalletMoney money;

	private final WalletGoals goals;

	private final Clock clock;

	public WalletTransactions(JdbcTemplate jdbc, WalletCategories categories,
			StoredFileRepository files, FileService fileService, WalletBudgets budgets, UserZones zones,
			LogoService logos, WalletMoney money, WalletGoals goals, Clock clock) {
		this.jdbc = jdbc;
		this.goals = goals;
		this.money = money;
		this.categories = categories;
		this.files = files;
		this.fileService = fileService;
		this.budgets = budgets;
		this.zones = zones;
		this.logos = logos;
		this.clock = clock;
	}

	/** One page; the totals of all matches are in my default currency. */
	@Transactional(readOnly = true)
	public WalletPage list(User user, ListFilter.Params params) {
		ZoneId zone = this.zones.saved(user.getId()).orElse(ZoneOffset.UTC);
		ListFilter filter = ListFilter.parse(params, YearMonth.now(this.clock.withZone(zone)));
		WalletMoney.Settings settings = this.money.settings(user.getId());
		TransactionSearch.Result result = SEARCH.run(this.jdbc, filter, "t.user_id = ?", List.of(user.getId()), null,
				settings::toDefault);
		return new WalletPage(load(user.getId(), result.ids()), result.totals(), settings.currency(),
				settings.usdRateText(), filter.page(), filter.size(), result.count(), result.hasNext(filter));
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
	 * @throws DuplicateException (409) one of my transactions has the same type, amount, currency and date, and
	 * confirmDuplicate is not true
	 */
	@Transactional
	public WalletTx create(User user, WalletTransactionRequest request) {
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
		checked.files().forEach((file) -> attach(file, id));
		this.budgets.checkAlerts(user.getId());
		this.logos.prefetch(checked.website());
		return get(user, id);
	}

	/**
	 * Full replace (no duplicate check). Files left out are deleted. Currency left out = keep.
	 * @throws ApiException 404 {@code transaction_not_found}, 400, 409 {@code goal_money} (made by a
	 * goal movement: change it on the Goals page)
	 */
	@Transactional
	public WalletTx update(User user, UUID id, WalletTransactionRequest request) {
		WalletTx existing = get(user, id);
		if (existing.goalId() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "goal_money", "Change it on the Goals page.");
		}
		Checked checked = check(user, request, existing);
		this.jdbc.update("UPDATE wallet_transactions SET type = ?, amount = ?, currency = ?, date = ?, description = ?, "
				+ "website = ?, category_id = ?, updated_at = ? WHERE id = ? AND user_id = ?", checked.type(),
				checked.amount(), checked.currency(), Date.valueOf(checked.date()), checked.description(),
				checked.website(), checked.categoryId(), Timestamp.from(this.clock.instant()), id, user.getId());
		Set<UUID> keep = checked.files().stream().map(StoredFile::getId).collect(Collectors.toSet());
		List<StoredFile> current = this.files.findAllByWalletTransactionIdInOrderByCreatedAtAsc(List.of(id));
		this.fileService.deleteNow(current.stream().filter((file) -> !keep.contains(file.getId())).toList());
		checked.files().forEach((file) -> attach(file, id));
		this.budgets.checkAlerts(user.getId());
		this.logos.prefetch(checked.website());
		return get(user, id);
	}

	/**
	 * Hard delete, with its files. Made by a goal movement: the movement goes too (same as its
	 * undo on the Goals page).
	 * @throws ApiException 404 {@code transaction_not_found}, 409 {@code not_enough_saved}
	 */
	@Transactional
	public void delete(User user, UUID id) {
		get(user, id);
		java.util.Optional<UUID[]> movement = this.goals.movementOf(id);
		if (movement.isPresent()) {
			this.goals.removeContribution(user.getId(), movement.get()[0], movement.get()[1]);
			return;
		}
		// Made by a recurring item: that date counts as skipped, so the item never makes it
		// again (the job, a change of the item or Resume would see "nothing for that date").
		this.jdbc.update("INSERT INTO wallet_recurring_skips (recurring_id, skip_date, created_at) "
				+ "SELECT recurring_id, recurring_date, ? FROM wallet_transactions "
				+ "WHERE id = ? AND user_id = ? AND recurring_id IS NOT NULL AND recurring_date IS NOT NULL "
				+ "ON CONFLICT DO NOTHING", Timestamp.from(this.clock.instant()), id, user.getId());
		this.fileService.deleteNow(this.files.findAllByWalletTransactionIdInOrderByCreatedAtAsc(List.of(id)));
		this.jdbc.update("DELETE FROM wallet_transactions WHERE id = ? AND user_id = ?", id, user.getId());
		// "Recurring item added" alert: its link would be dead.
		this.jdbc.update("DELETE FROM notifications WHERE user_id = ? AND link = ?", user.getId(),
				"/wallet/transactions/" + id);
	}

	/** INCOME - EXPENSE of all my transactions, in the default currency of {@code settings} (not rounded). */
	@Transactional(readOnly = true)
	public BigDecimal balance(UUID userId, WalletMoney.Settings settings) {
		Map<String, BigDecimal> sums = sums(userId, null, null, settings);
		return sums.getOrDefault("INCOME", BigDecimal.ZERO).subtract(sums.getOrDefault("EXPENSE", BigDecimal.ZERO));
	}

	/** In/out/net between two dates (included), in the default currency of {@code settings}. */
	@Transactional(readOnly = true)
	public Totals totals(UUID userId, LocalDate from, LocalDate to, WalletMoney.Settings settings) {
		return Totals.fromSums(sums(userId, from, to, settings));
	}

	/** Sums per type, each currency converted into the default currency (not rounded). */
	private Map<String, BigDecimal> sums(UUID userId, LocalDate from, LocalDate to, WalletMoney.Settings settings) {
		List<Object> args = new ArrayList<>(List.of(userId));
		String where = "user_id = ?";
		if (from != null) {
			where += " AND date >= ? AND date <= ?";
			args.add(Date.valueOf(from));
			args.add(Date.valueOf(to));
		}
		Map<String, BigDecimal> sums = new HashMap<>();
		this.jdbc.query("SELECT type, currency, sum(amount) AS total FROM wallet_transactions WHERE " + where
				+ " GROUP BY type, currency", (rs) -> {
					sums.merge(rs.getString("type"),
							settings.toDefault(rs.getBigDecimal("total"), rs.getString("currency")), BigDecimal::add);
				}, args.toArray());
		return sums;
	}

	// ---- checks ----

	private Checked check(User user, WalletTransactionRequest request, WalletTx existing) {
		BigDecimal amount = Money.parse("amount", request.amount());
		String currency = WalletMoney.pick(request.currency(),
				(existing != null) ? existing.currency() : this.money.settings(user.getId()).currency());
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
		if (!keepsOld || !existing.type().equals(request.type())) {
			CategoryKinds.checkFits(category.kind(), request.type());
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
		return new Checked(request.type(), amount, currency, date, request.description(), website,
				category.id(), foundFiles);
	}

	private List<DuplicateException.Match> duplicates(User user, Checked checked) {
		DuplicateException.UserRef me = new DuplicateException.UserRef(user.getId(), user.displayName(),
				user.avatarSeed());
		return this.jdbc.query(
				"SELECT id, description, created_at FROM wallet_transactions WHERE user_id = ? AND type = ? AND amount = ? "
						+ "AND currency = ? AND date = ? ORDER BY created_at DESC LIMIT 5",
				(rs, row) -> new DuplicateException.Match(rs.getObject("id", UUID.class), rs.getString("description"), me,
						rs.getTimestamp("created_at").toInstant()),
				user.getId(), checked.type(), checked.amount(), checked.currency(), Date.valueOf(checked.date()));
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
		Map<String, List<String>> logoUrls = this.logos.logoUrls(domains);
		Map<UUID, UUID> goalIds = new HashMap<>();
		this.jdbc.query("SELECT transaction_id, goal_id FROM wallet_goal_contributions WHERE transaction_id = ANY (?)",
				(rs) -> {
					goalIds.put(rs.getObject("transaction_id", UUID.class), rs.getObject("goal_id", UUID.class));
				}, (Object) idArray);
		List<WalletTx> result = new ArrayList<>();
		for (UUID id : ids) {
			Row row = rows.get(id);
			if (row == null) {
				continue;
			}
			WalletCategories.Category category = categoriesById.get(row.categoryId());
			WalletCategories.Category parent = (category != null && category.parentId() != null)
					? categoriesById.get(category.parentId()) : null;
			List<String> txLogoUrls = LogoService.chain(logoUrls, row.website(),
					(category != null) ? category.website() : null, (parent != null) ? parent.website() : null);
			CategoryRef categoryRef = (category != null) ? new CategoryRef(category.id(), category.name(),
					category.icon(), category.archived(), category.parentId(), category.kind()) : null;
			result.add(new WalletTx(id, row.type(), Totals.money(row.amount()), row.currency(), row.date(),
					row.description(), row.website(), txLogoUrls, categoryRef, filesByTx.getOrDefault(id, List.of()), row.recurringId(),
					goalIds.get(id), row.createdAt(), row.updatedAt()));
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
			String website, UUID categoryId, List<StoredFile> files) {
	}

	/**
	 * A wallet transaction as the API returns it.
	 * @param amount in {@code currency}
	 * @param currency USD or MAD
	 * @param website bare domain like "claude.ai", or null
	 * @param logoUrls logo image URLs to try in the browser, in order; empty = category icon
	 * @param recurringId the recurring item that made it (null if added by hand)
	 * @param goalId the goal whose movement made it (null for a normal transaction); such a
	 * transaction cannot be changed here (409 goal_money), deleting it removes the movement
	 * @param updatedAt null if never changed
	 */
	public record WalletTx(UUID id, String type, String amount, String currency, LocalDate date, String description,
			String website, List<String> logoUrls, CategoryRef category, List<FileResponse> files, UUID recurringId,
			@io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "The goal whose movement made "
					+ "it (add = EXPENSE, take out = INCOME); null for a normal transaction. PUT gives 409 goal_money; "
					+ "DELETE also removes the goal movement.") UUID goalId,
			Instant createdAt, Instant updatedAt) {
	}

	/**
	 * One page of my wallet transactions.
	 * @param totals in/out/net of ALL matching items, in {@code currency} (converted with {@code usdRate})
	 * @param currency my default wallet currency (the currency of the totals)
	 * @param usdRate my rate, 1 USD = usdRate MAD, e.g. "10.00"
	 * @param page 0-based
	 * @param totalItems all matching items
	 */
	public record WalletPage(List<WalletTx> items, Totals totals, String currency, String usdRate, int page, int size,
			long totalItems, boolean hasNext) {
	}

}
