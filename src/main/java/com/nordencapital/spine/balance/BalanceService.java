package com.nordencapital.spine.balance;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.balance.BalanceApi.Balance;
import com.nordencapital.spine.balance.BalanceApi.BalanceDetail;
import com.nordencapital.spine.balance.BalanceApi.BalanceList;
import com.nordencapital.spine.balance.BalanceApi.BalanceRequest;
import com.nordencapital.spine.balance.BalanceApi.FixRequest;
import com.nordencapital.spine.balance.BalanceApi.HistoryItem;
import com.nordencapital.spine.balance.BalanceApi.RechargeRequest;
import com.nordencapital.spine.balance.BalanceApi.Summary;
import com.nordencapital.spine.category.Category;
import com.nordencapital.spine.category.CategoryRepository;
import com.nordencapital.spine.category.CategoryService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.CategoryKinds;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Recurrence;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.logo.LogoService;
import com.nordencapital.spine.transaction.CommentService;
import com.nordencapital.spine.transaction.TransactionRequest;
import com.nordencapital.spine.transaction.TransactionResponse;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.transaction.TransactionResponse.UserRef;
import com.nordencapital.spine.transaction.TransactionService;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.Avatars;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;

/**
 * Balances (company space): prepaid accounts a service takes money from on a schedule.
 * Spine does not know the real balance: it guesses it from the last start point (create,
 * recharge or fix) and the cut dates since. Everyone reads; only the OWNER changes.
 * "Today" is the company day, in Morocco time.
 */
@Service
public class BalanceService {

	public static final String BALANCE_LOW = "BALANCE_LOW";

	public static final String BALANCE_SOON = "BALANCE_SOON";

	public static final String BALANCE_EMPTY = "BALANCE_EMPTY";

	/** The company day. */
	static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");

	private static final Pattern MONEY_OR_ZERO = Pattern.compile(Money.PATTERN);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private static final String COLUMNS = "id, name, website, category_id, cut, repeat_unit, repeat_every, anchor_date, "
			+ "low_limit, warn_days, note, start_amount, start_date, start_seq, created_by, created_at, updated_by, "
			+ "updated_at";

	private static final Logger logger = LoggerFactory.getLogger(BalanceService.class);

	private final JdbcTemplate jdbc;

	private final CategoryRepository categories;

	private final CategoryService categoryService;

	private final UserRepository users;

	private final LogoService logos;

	private final AlertService alerts;

	private final TransactionService transactions;

	private final CommentService comments;

	private final Actors actors;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public BalanceService(JdbcTemplate jdbc, CategoryRepository categories, CategoryService categoryService,
			UserRepository users, LogoService logos, AlertService alerts, TransactionService transactions,
			CommentService comments, Actors actors, TransactionTemplate transaction, Clock clock) {
		this.jdbc = jdbc;
		this.categories = categories;
		this.categoryService = categoryService;
		this.users = users;
		this.logos = logos;
		this.alerts = alerts;
		this.transactions = transactions;
		this.comments = comments;
		this.actors = actors;
		this.transaction = transaction;
		this.clock = clock;
	}

	// ---- reading (everyone) ----

	/** All balances, most urgent first, with the summary. */
	@Transactional(readOnly = true)
	public BalanceList list(AuthUser principal) {
		User viewer = this.actors.active(principal);
		List<Row> rows = this.jdbc.query("SELECT " + COLUMNS + " FROM balances", BalanceService::map);
		Views views = views(rows, viewer);
		List<Balance> items = new ArrayList<>(rows.stream().map(views::balance).toList());
		items.sort(URGENT_FIRST);
		BigDecimal perMonth = BigDecimal.ZERO.setScale(2);
		int red = 0;
		int amber = 0;
		for (Balance item : items) {
			perMonth = perMonth.add(new BigDecimal(item.perMonth()));
			red += "RED".equals(item.urgency()) ? 1 : 0;
			amber += "AMBER".equals(item.urgency()) ? 1 : 0;
		}
		return new BalanceList(new Summary(items.size(), Totals.money(perMonth), red + amber, red, amber), items);
	}

	/** @throws ApiException 404 {@code balance_not_found} */
	@Transactional(readOnly = true)
	public BalanceDetail get(AuthUser principal, UUID id) {
		User viewer = this.actors.active(principal);
		Row row = row(id, false);
		List<EventRow> events = this.jdbc.query("SELECT id, kind, event_date, amount, guess_before, start_after, "
				+ "transaction_id, user_id FROM balance_events WHERE balance_id = ? ORDER BY event_date, created_at, id",
				(rs, n) -> new EventRow(rs.getString("kind"), rs.getDate("event_date").toLocalDate(),
						rs.getBigDecimal("amount"), rs.getBigDecimal("guess_before"), rs.getBigDecimal("start_after"),
						rs.getObject("transaction_id", UUID.class), rs.getObject("user_id", UUID.class)),
				id);
		Set<UUID> userIds = new HashSet<>();
		events.forEach((e) -> userIds.add(e.userId()));
		Views views = views(List.of(row), viewer, userIds);
		return new BalanceDetail(views.balance(row), history(row, events, today(), views));
	}

	/**
	 * Newest first: the events, and between them the cuts Spine guessed (grouped).
	 */
	private static List<HistoryItem> history(Row row, List<EventRow> events, LocalDate today, Views views) {
		BalanceMath math = row.math();
		List<HistoryItem> items = new ArrayList<>();
		for (int i = 0; i < events.size(); i++) {
			EventRow e = events.get(i);
			items.add(new HistoryItem(e.kind(), e.date(), Totals.money(e.amount()),
					(e.guessBefore() != null) ? Totals.money(e.guessBefore()) : null, Totals.money(e.startAfter()),
					e.transactionId(), views.user(e.userId()), null, null, null, null, null));
			LocalDate to = (i + 1 < events.size()) ? events.get(i + 1).date() : today;
			long count = math.cutsBetween(e.date(), to);
			if (count > 0) {
				LocalDate first = math.nextCutAfter(e.date());
				LocalDate last = math.cut(math.firstAfter(to) - 1);
				BigDecimal total = row.cut().multiply(BigDecimal.valueOf(count)).min(e.startAfter());
				items.add(new HistoryItem("CUTS", last, null, null, null, null, null, count, first, last,
						Totals.money(row.cut()), Totals.money(total)));
			}
		}
		Collections.reverse(items);
		return items;
	}

	// ---- changes (OWNER) ----

	/** @throws ApiException 400 {@code validation_error} */
	@Transactional
	public BalanceDetail create(AuthUser principal, BalanceRequest request) {
		User actor = this.actors.owner(principal);
		LocalDate today = today();
		Valid valid = check(request, null, today);
		if (request.startAmount() == null) {
			throw invalid("startAmount: the balance now is required");
		}
		BigDecimal start = moneyOrZero("startAmount", request.startAmount());
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO balances (id, name, website, category_id, cut, repeat_unit, repeat_every, "
				+ "anchor_date, low_limit, warn_days, note, start_amount, start_date, start_seq, created_by, created_at, "
				+ "updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)", id,
				request.name(), valid.website(), request.categoryId(), valid.cut(), request.unit(), request.every(),
				Date.valueOf(valid.anchor()), valid.lowLimit(), valid.warnDays(), valid.note(), start,
				Date.valueOf(today), actor.getId(), now, actor.getId(), now);
		event(id, "CREATED", today, start, null, start, null, actor.getId());
		this.logos.prefetch(valid.website());
		checkAlerts(id);
		return get(principal, id);
	}

	/**
	 * Full replace (the amount is changed with fix, not here). When the cut, how often or
	 * the next cut changes, the guess of today becomes the new start (so the past is not
	 * counted again with the new numbers).
	 * @throws ApiException 404 {@code balance_not_found}, 400 {@code validation_error}
	 */
	@Transactional
	public BalanceDetail update(AuthUser principal, UUID id, BalanceRequest request) {
		User actor = this.actors.owner(principal);
		Row row = row(id, true);
		LocalDate today = today();
		Valid valid = check(request, row, today);
		BigDecimal start = row.startAmount();
		LocalDate startDate = row.startDate();
		boolean planChanged = !row.unit().equals(request.unit()) || row.every() != request.every()
				|| row.cut().compareTo(valid.cut()) != 0 || !row.anchor().equals(valid.anchor());
		if (planChanged && today.isAfter(row.startDate())) {
			start = row.math().guess(row.startAmount(), row.startDate(), row.cut(), today);
			startDate = today;
		}
		this.jdbc.update("UPDATE balances SET name = ?, website = ?, category_id = ?, cut = ?, repeat_unit = ?, "
				+ "repeat_every = ?, anchor_date = ?, low_limit = ?, warn_days = ?, note = ?, start_amount = ?, "
				+ "start_date = ?, updated_by = ?, updated_at = ? WHERE id = ?", request.name(), valid.website(),
				request.categoryId(), valid.cut(), request.unit(), request.every(), Date.valueOf(valid.anchor()),
				valid.lowLimit(), valid.warnDays(), valid.note(), start, Date.valueOf(startDate), actor.getId(),
				Timestamp.from(this.clock.instant()), id);
		this.logos.prefetch(valid.website());
		checkAlerts(id);
		return get(principal, id);
	}

	/**
	 * Deletes the balance, its history and its alerts. The recharge expenses stay.
	 * @throws ApiException 404 {@code balance_not_found}
	 */
	@Transactional
	public void delete(AuthUser principal, UUID id) {
		this.actors.owner(principal);
		row(id, true);
		this.jdbc.update("DELETE FROM notifications WHERE dedupe_key LIKE ?", "balance:" + id + ":%");
		this.jdbc.update("DELETE FROM balances WHERE id = ?", id);
	}

	/**
	 * Recharge: one company expense (amount, date, the balance's category and website,
	 * "<name> recharge", the proof files) and a new start point = guess on that date +
	 * amount. All or nothing.
	 * @throws ApiException 404 {@code balance_not_found}, 400 {@code validation_error},
	 * {@code proof_required}, {@code future_date}
	 */
	@Transactional
	public BalanceDetail recharge(AuthUser principal, UUID id, RechargeRequest request) {
		User actor = this.actors.owner(principal);
		Row row = row(id, true);
		BigDecimal amount = Money.parse("amount", request.amount());
		LocalDate date = Recurrence.parseDate(request.date());
		if (date.isBefore(row.startDate())) {
			throw invalid("date: must not be before " + row.startDate() + " (the last recharge or fix)");
		}
		TransactionResponse expense = this.transactions.create(principal,
				new TransactionRequest("EXPENSE", Totals.money(amount), date.toString(), row.name() + " recharge",
						row.categoryId(), request.fileIds(), row.website(), true, null));
		BigDecimal before = row.math().guess(row.startAmount(), row.startDate(), row.cut(), date);
		BigDecimal after = before.add(amount);
		if (after.compareTo(Money.MAX) > 0) {
			throw invalid("amount: the balance would be too big");
		}
		newStart(row, after, date, actor);
		event(id, "RECHARGE", date, amount, before, after, expense.id(), actor.getId());
		String note = (request.note() != null) ? request.note().strip() : "";
		if (!note.isEmpty()) {
			this.comments.add(principal, expense.id(), note);
		}
		checkAlerts(id);
		return get(principal, id);
	}

	/**
	 * Fix the real balance: new start point = the typed amount, today. No expense.
	 * @throws ApiException 404 {@code balance_not_found}, 400 {@code validation_error}
	 */
	@Transactional
	public BalanceDetail fix(AuthUser principal, UUID id, FixRequest request) {
		User actor = this.actors.owner(principal);
		Row row = row(id, true);
		BigDecimal amount = moneyOrZero("amount", request.amount());
		LocalDate today = today();
		BigDecimal before = row.math().guess(row.startAmount(), row.startDate(), row.cut(), today);
		newStart(row, amount, today, actor);
		event(id, "FIX", today, amount, before, amount, null, actor.getId());
		checkAlerts(id);
		return get(principal, id);
	}

	private void newStart(Row row, BigDecimal start, LocalDate date, User actor) {
		this.jdbc.update("UPDATE balances SET start_amount = ?, start_date = ?, start_seq = start_seq + 1, "
				+ "updated_by = ?, updated_at = ? WHERE id = ?", start, Date.valueOf(date), actor.getId(),
				Timestamp.from(this.clock.instant()), row.id());
		// The alerts of the old start point are handled.
		this.jdbc.update("UPDATE notifications SET read_at = coalesce(read_at, ?) WHERE dedupe_key LIKE ?",
				Timestamp.from(this.clock.instant()), alertPrefix(row.id(), row.startSeq()) + "%");
	}

	private void event(UUID balanceId, String kind, LocalDate date, BigDecimal amount, BigDecimal guessBefore,
			BigDecimal startAfter, UUID transactionId, UUID userId) {
		this.jdbc.update("INSERT INTO balance_events (id, balance_id, kind, event_date, amount, guess_before, "
				+ "start_after, transaction_id, user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), balanceId, kind, Date.valueOf(date), amount, guessBefore, startAfter, transactionId,
				userId, Timestamp.from(this.clock.instant()));
	}

	// ---- alerts ----

	/**
	 * Every hour (Morocco day): the balance alerts, each once per start point. Safe to run
	 * again (dedupe keys).
	 */
	@Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1H")
	public void runAlerts() {
		for (UUID id : this.jdbc.queryForList("SELECT id FROM balances", UUID.class)) {
			try {
				this.transaction.executeWithoutResult((status) -> checkAlerts(id));
			}
			catch (RuntimeException ex) {
				logger.error("Balance {} alerts failed: {}", id, ex.getMessage());
			}
		}
	}

	/**
	 * COMPANY alerts to everyone: BALANCE_EMPTY (guess is $0), else BALANCE_LOW (under the
	 * low limit) and BALANCE_SOON (empty within the warn days).
	 * @return how many alerts were added (all people)
	 */
	int checkAlerts(UUID id) {
		Row row = this.jdbc.query("SELECT " + COLUMNS + " FROM balances WHERE id = ?", BalanceService::map, id)
			.stream()
			.findFirst()
			.orElse(null);
		if (row == null) {
			return 0;
		}
		LocalDate today = today();
		State state = state(row, today);
		String key = alertPrefix(id, row.startSeq());
		String link = "/balances/" + id;
		if (state.guess().signum() <= 0) {
			return this.alerts.addForCompany(BALANCE_EMPTY, row.name() + " is empty",
					"Spine guesses $0.00 since " + DAY.format(state.emptyDate()) + ". Recharge it.", link, key + "empty");
		}
		int added = 0;
		if (row.lowLimit() != null && state.guess().compareTo(row.lowLimit()) < 0) {
			added += this.alerts.addForCompany(BALANCE_LOW, row.name() + " is below $" + Totals.money(row.lowLimit()),
					"Guessed balance: $" + Totals.money(state.guess()) + ".", link, key + "low");
		}
		if (state.daysLeft() != null && state.daysLeft() <= row.warnDays()) {
			added += this.alerts.addForCompany(BALANCE_SOON,
					row.name() + " runs out in " + state.daysLeft() + ((state.daysLeft() == 1) ? " day" : " days"),
					"Around " + DAY.format(state.emptyDate()) + ". Guessed balance: $" + Totals.money(state.guess())
							+ ".",
					link, key + "soon");
		}
		return added;
	}

	private static String alertPrefix(UUID id, int startSeq) {
		return "balance:" + id + ":" + startSeq + ":";
	}

	// ---- helpers ----

	LocalDate today() {
		return LocalDate.now(this.clock.withZone(ZONE));
	}

	static State state(Row row, LocalDate today) {
		BalanceMath math = row.math();
		BigDecimal guess = math.guess(row.startAmount(), row.startDate(), row.cut(), today);
		LocalDate empty = math.emptyDate(row.startAmount(), row.startDate(), row.cut());
		LocalDate low = (row.lowLimit() != null)
				? math.belowLimitDate(row.startAmount(), row.startDate(), row.cut(), row.lowLimit()) : null;
		Long daysLeft = (empty != null) ? ChronoUnit.DAYS.between(today, empty) : null;
		int percent = (row.startAmount().signum() > 0) ? guess.multiply(BigDecimal.valueOf(100))
			.divide(row.startAmount(), 0, java.math.RoundingMode.HALF_UP)
			.intValue() : 0;
		return new State(guess, empty, low, daysLeft, math.nextCutAfter(today), math.perMonth(row.cut()), percent,
				BalanceMath.urgency(guess, row.cut(), row.lowLimit(), empty, row.warnDays(), today));
	}

	private Valid check(BalanceRequest request, Row existing, LocalDate today) {
		BigDecimal cut = Money.parse("cut", request.cut());
		if (BalanceMath.TIMES_A_MONTH.equals(request.unit())) {
			if (request.every() < 1 || request.every() > 31) {
				throw invalid("every: TIMES_A_MONTH must be 1 to 31");
			}
		}
		else {
			Recurrence.check(request.unit(), request.every());
		}
		Category category = this.categories.findById(request.categoryId())
			.orElseThrow(() -> invalid("categoryId: unknown category"));
		boolean keepsOld = existing != null && existing.categoryId().equals(category.getId());
		if (!keepsOld && !this.categoryService.isPickable(category)) {
			throw invalid("categoryId: this category (or its parent) is archived");
		}
		CategoryKinds.checkFits(category.getKind(), "EXPENSE");
		BigDecimal lowLimit = (request.lowLimit() != null && !request.lowLimit().isBlank())
				? moneyOrZero("lowLimit", request.lowLimit()) : null;
		int warnDays = (request.warnDays() != null) ? request.warnDays() : 7;
		if (warnDays < 0 || warnDays > 365) {
			throw invalid("warnDays: must be 0 to 365");
		}
		String note = (request.note() != null && !request.note().isBlank()) ? request.note().strip() : null;
		return new Valid(cut, Websites.normalize(request.website()), anchor(request, existing, today), lowLimit,
				warnDays, note);
	}

	/**
	 * The first cut date of the series. Empty = keep the series (edit, same how often) or
	 * one step after today. When the date sent is the next cut the series already has,
	 * the series is kept (no drift).
	 */
	private static LocalDate anchor(BalanceRequest request, Row existing, LocalDate today) {
		boolean sameRepeat = existing != null && existing.unit().equals(request.unit())
				&& existing.every() == request.every();
		if (request.nextCut() == null || request.nextCut().isBlank()) {
			return sameRepeat ? existing.anchor() : BalanceMath.oneStepAfter(request.unit(), request.every(), today);
		}
		LocalDate next = Recurrence.parseDate(request.nextCut());
		if (sameRepeat && (next.equals(existing.anchor()) || next.equals(existing.math().nextCutAfter(today)))) {
			return existing.anchor();
		}
		if (!next.isAfter(today)) {
			throw invalid("nextCut: must be after today");
		}
		if (next.isAfter(today.plusYears(5))) {
			throw invalid("nextCut: must be within 5 years");
		}
		return next;
	}

	/** Money that may be 0 (balance now, fix, low limit). */
	private static BigDecimal moneyOrZero(String field, String text) {
		if (text != null && MONEY_OR_ZERO.matcher(text).matches() && new BigDecimal(text).signum() == 0) {
			return BigDecimal.ZERO.setScale(2);
		}
		return Money.parse(field, text);
	}

	private Row row(UUID id, boolean lock) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM balances WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
				BalanceService::map, id)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "balance_not_found", "Balance not found"));
	}

	private Views views(List<Row> rows, User viewer) {
		return views(rows, viewer, Set.of());
	}

	/** Categories (with parents), logos and people for these rows, loaded at once. */
	private Views views(List<Row> rows, User viewer, Set<UUID> moreUsers) {
		Map<UUID, Category> byId = new HashMap<>();
		this.categories.findAllById(rows.stream().map(Row::categoryId).distinct().toList())
			.forEach((c) -> byId.put(c.getId(), c));
		this.categories.findAllById(byId.values().stream().map(Category::getParentId).filter(Objects::nonNull).toList())
			.forEach((c) -> byId.put(c.getId(), c));
		List<String> domains = new ArrayList<>();
		rows.forEach((row) -> domains.add(row.website()));
		byId.values().forEach((c) -> domains.add(c.getWebsite()));
		domains.removeIf(Objects::isNull);
		Set<UUID> userIds = new HashSet<>(moreUsers);
		rows.forEach((row) -> {
			userIds.add(row.createdBy());
			userIds.add(row.updatedBy());
		});
		userIds.remove(null);
		Map<UUID, User> people = new HashMap<>();
		this.users.findAllById(userIds).forEach((u) -> people.put(u.getId(), u));
		return new Views(byId, this.logos.logoUrls(domains), people, viewer.getRole() == Role.OWNER, today());
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	private static Row map(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("website"),
				rs.getObject("category_id", UUID.class), rs.getBigDecimal("cut"), rs.getString("repeat_unit"),
				rs.getInt("repeat_every"), rs.getDate("anchor_date").toLocalDate(), rs.getBigDecimal("low_limit"),
				rs.getInt("warn_days"), rs.getString("note"), rs.getBigDecimal("start_amount"),
				rs.getDate("start_date").toLocalDate(), rs.getInt("start_seq"), rs.getObject("created_by", UUID.class),
				rs.getTimestamp("created_at").toInstant(), rs.getObject("updated_by", UUID.class),
				rs.getTimestamp("updated_at").toInstant());
	}

	private static final Map<String, Integer> URGENCY_ORDER = Map.of("RED", 0, "AMBER", 1, "GREEN", 2);

	/** RED, then AMBER, then GREEN; then the soonest empty date; then the name. */
	static final Comparator<Balance> URGENT_FIRST = Comparator
		.comparing((Balance b) -> URGENCY_ORDER.getOrDefault(b.urgency(), 3))
		.thenComparing(Balance::emptyDate, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(Balance::name, String.CASE_INSENSITIVE_ORDER);

	record Row(UUID id, String name, String website, UUID categoryId, BigDecimal cut, String unit, int every,
			LocalDate anchor, BigDecimal lowLimit, int warnDays, String note, BigDecimal startAmount,
			LocalDate startDate, int startSeq, UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt) {

		BalanceMath math() {
			return new BalanceMath(this.unit, this.every, this.anchor);
		}

	}

	record State(BigDecimal guess, LocalDate emptyDate, LocalDate belowLimitDate, Long daysLeft, LocalDate nextCut,
			BigDecimal perMonth, int percent, String urgency) {
	}

	private record EventRow(String kind, LocalDate date, BigDecimal amount, BigDecimal guessBefore,
			BigDecimal startAfter, UUID transactionId, UUID userId) {
	}

	private record Valid(BigDecimal cut, String website, LocalDate anchor, BigDecimal lowLimit, int warnDays,
			String note) {
	}

	private record Views(Map<UUID, Category> categories, Map<String, List<String>> logoUrls, Map<UUID, User> people,
			boolean canEdit, LocalDate today) {

		CategoryRef category(Row row) {
			Category c = this.categories.get(row.categoryId());
			return (c != null)
					? new CategoryRef(c.getId(), c.getName(), c.getIcon(), c.isArchived(), c.getParentId(), c.getKind())
					: null;
		}

		/** Own website logo, then the category's, then the parent's (like transactions). */
		List<String> logoUrls(Row row) {
			Category c = this.categories.get(row.categoryId());
			Category parent = (c != null && c.getParentId() != null) ? this.categories.get(c.getParentId()) : null;
			return LogoService.chain(this.logoUrls, row.website(), (c != null) ? c.getWebsite() : null,
					(parent != null) ? parent.getWebsite() : null);
		}

		UserRef user(UUID id) {
			if (id == null) {
				return null;
			}
			User u = this.people.get(id);
			return new UserRef(id, (u != null) ? u.displayName() : null,
					(u != null) ? u.avatarSeed() : Avatars.seed(id, null));
		}

		Balance balance(Row row) {
			State s = state(row, this.today);
			return new Balance(row.id(), row.name(), row.website(), logoUrls(row), category(row),
					Totals.money(row.cut()), row.unit(), row.every(), s.nextCut(),
					(row.lowLimit() != null) ? Totals.money(row.lowLimit()) : null, row.warnDays(), row.note(),
					Totals.money(row.startAmount()), row.startDate(), Totals.money(s.guess()), s.percent(),
					Totals.money(s.perMonth()), s.emptyDate(), s.daysLeft(), s.guess().signum() <= 0,
					s.belowLimitDate(), s.urgency(), user(row.createdBy()), row.createdAt(), user(row.updatedBy()),
					row.updatedAt(), this.canEdit);
		}

	}

}
