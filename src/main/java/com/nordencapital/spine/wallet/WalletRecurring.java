package com.nordencapital.spine.wallet;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.CategoryKinds;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.Recurrence;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.me.UserZones;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;

/**
 * Recurring wallet transactions (rent, salary...). A job runs every hour: for each
 * active item due today or earlier (in its owner's time zone, UTC if automatic) it
 * creates the wallet transaction for that date (never twice for the same date), moves
 * the next date on and adds an alert. It also warns one day before.
 */
@Component
public class WalletRecurring {

	/** How many next dates the API shows. */
	static final int UPCOMING_COUNT = 6;

	/** Safety stop for one item in one run (more than a year of weekly items). */
	private static final int MAX_CATCH_UP = 60;

	private static final Logger logger = LoggerFactory.getLogger(WalletRecurring.class);

	private static final String COLUMNS = "id, user_id, name, type, amount, category_id, description, "
			+ "repeat_unit, repeat_every, start_date, next_date, active, paused_until";

	private final JdbcTemplate jdbc;

	private final WalletCategories categories;

	private final WalletBudgets budgets;

	private final AlertService alerts;

	private final UserZones zones;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public WalletRecurring(JdbcTemplate jdbc, WalletCategories categories, WalletBudgets budgets, AlertService alerts,
			UserZones zones, TransactionTemplate transaction, Clock clock) {
		this.jdbc = jdbc;
		this.categories = categories;
		this.budgets = budgets;
		this.alerts = alerts;
		this.zones = zones;
		this.transaction = transaction;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Recurring> list(UUID userId) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM wallet_recurring WHERE user_id = ? ORDER BY next_date, name",
				WalletRecurring::map, userId).stream().map((row) -> view(userId, row)).toList();
	}

	/** @throws ApiException 400 {@code validation_error} */
	@Transactional
	public Recurring create(UUID userId, RecurringRequest request) {
		Valid valid = check(userId, request, null);
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO wallet_recurring (id, user_id, name, type, amount, category_id, description, "
				+ "repeat_unit, repeat_every, start_date, next_date, active, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)", id, userId, request.name(), request.type(),
				valid.amount(), request.categoryId(), request.description(), request.unit(), request.every(),
				Date.valueOf(valid.start()),
				Date.valueOf(nextOnOrAfter(valid.start(), request.unit(), request.every(), today(userId))), now, now);
		runOne(id);
		return one(userId, id);
	}

	/** Full replace; the next date is calculated again. @throws ApiException 404, 400 */
	@Transactional
	public Recurring update(UUID userId, UUID id, RecurringRequest request) {
		Row existing = mine(userId, id);
		Valid valid = check(userId, request, existing);
		this.jdbc.update("UPDATE wallet_recurring SET name = ?, type = ?, amount = ?, category_id = ?, "
				+ "description = ?, repeat_unit = ?, repeat_every = ?, start_date = ?, next_date = ?, updated_at = ? WHERE id = ? AND user_id = ?",
				request.name(), request.type(), valid.amount(), request.categoryId(),
				request.description(), request.unit(), request.every(), Date.valueOf(valid.start()),
				Date.valueOf(nextOnOrAfter(valid.start(), request.unit(), request.every(), from(existing, userId))),
				Timestamp.from(this.clock.instant()), id, userId);
		runOne(id);
		return one(userId, id);
	}

	/** Transactions it already made stay (their recurringId becomes null). @throws 404 */
	@Transactional
	public void delete(UUID userId, UUID id) {
		mine(userId, id);
		this.jdbc.update("DELETE FROM wallet_recurring WHERE id = ? AND user_id = ?", id, userId);
	}

	/**
	 * Pause or resume. Resume does not make the missed dates: the next date becomes the
	 * next one on or after today.
	 * @throws ApiException 404 {@code recurring_not_found}
	 */
	@Transactional
	public Recurring setActive(UUID userId, UUID id, boolean active) {
		Row row = mine(userId, id);
		boolean pausedUntil = row.pausedUntil() != null && row.pausedUntil().isAfter(today(userId));
		if (active && (!row.active() || pausedUntil)) {
			this.jdbc.update("UPDATE wallet_recurring SET active = true, paused_until = NULL, next_date = ?, "
					+ "updated_at = ? WHERE id = ?",
					Date.valueOf(nextOnOrAfter(row.startDate(), row.unit(), row.every(), today(userId))),
					Timestamp.from(this.clock.instant()), id);
			runOne(id);
		}
		else if (!active && (row.active() || row.pausedUntil() != null)) {
			this.jdbc.update("UPDATE wallet_recurring SET active = false, paused_until = NULL, updated_at = ? WHERE id = ?",
					Timestamp.from(this.clock.instant()), id);
		}
		return one(userId, id);
	}

	/**
	 * Pause until a date: no transaction before it; from that date the series goes on
	 * by itself.
	 * @throws ApiException 404 {@code recurring_not_found}, 400 {@code validation_error}
	 * (not after today)
	 */
	@Transactional
	public Recurring pauseUntil(UUID userId, UUID id, String dateText) {
		Row row = mine(userId, id);
		LocalDate date = Recurrence.parseDate(dateText);
		if (!date.isAfter(today(userId))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "date: must be after today");
		}
		this.jdbc.update("UPDATE wallet_recurring SET active = true, paused_until = ?, next_date = ?, updated_at = ? "
				+ "WHERE id = ?", Date.valueOf(date),
				Date.valueOf(nextOnOrAfter(row.startDate(), row.unit(), row.every(), date)),
				Timestamp.from(this.clock.instant()), id);
		return one(userId, id);
	}

	/**
	 * Skip one date of the series: its transaction is not made; the next ones are normal.
	 * @throws ApiException 404 {@code recurring_not_found}, 400 {@code validation_error}
	 * (not a date of the series, or in the past), 409 {@code already_created}
	 */
	@Transactional
	public Recurring skip(UUID userId, UUID id, String dateText) {
		Row row = mine(userId, id);
		LocalDate date = Recurrence.parseDate(dateText);
		if (!Recurrence.isSeriesDate(row.startDate(), row.unit(), row.every(), date)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "date: not a date of this series");
		}
		if (date.isBefore(today(userId))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "date: must be today or later");
		}
		Boolean made = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM wallet_transactions WHERE "
				+ "recurring_id = ? AND recurring_date = ?)", Boolean.class, id, Date.valueOf(date));
		if (Boolean.TRUE.equals(made)) {
			throw new ApiException(HttpStatus.CONFLICT, "already_created",
					"This one was already added to your wallet (delete that transaction instead)");
		}
		this.jdbc.update("INSERT INTO wallet_recurring_skips (recurring_id, skip_date, created_at) VALUES (?, ?, ?) "
				+ "ON CONFLICT DO NOTHING", id, Date.valueOf(date), Timestamp.from(this.clock.instant()));
		return one(userId, id);
	}

	/**
	 * Undo a skip, before its date.
	 * @throws ApiException 404 {@code recurring_not_found}, 409 {@code too_late}
	 */
	@Transactional
	public Recurring unskip(UUID userId, UUID id, String dateText) {
		mine(userId, id);
		LocalDate date = Recurrence.parseDate(dateText);
		if (!date.isAfter(today(userId))) {
			throw new ApiException(HttpStatus.CONFLICT, "too_late", "A skip can be undone only before its date");
		}
		this.jdbc.update("DELETE FROM wallet_recurring_skips WHERE recurring_id = ? AND skip_date = ?", id,
				Date.valueOf(date));
		return one(userId, id);
	}

	private java.util.Set<LocalDate> skips(UUID id) {
		return new java.util.HashSet<>(this.jdbc.queryForList(
				"SELECT skip_date FROM wallet_recurring_skips WHERE recurring_id = ?", LocalDate.class, id));
	}

	/** Dates are calculated from today, or from the "paused until" date while it is in the future. */
	private LocalDate from(Row row, UUID userId) {
		LocalDate today = today(userId);
		return (row.pausedUntil() != null && row.pausedUntil().isAfter(today)) ? row.pausedUntil() : today;
	}

	/**
	 * Every hour: every active item that is due (or due tomorrow, for the warning).
	 * Each item runs in its own transaction, so one failure does not stop the others.
	 * @return how many transactions were created
	 */
	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
	public int runDue() {
		LocalDate latestTomorrow = LocalDate.now(this.clock.withZone(ZoneOffset.ofHours(14))).plusDays(1);
		List<UUID> due = this.jdbc.queryForList("SELECT id FROM wallet_recurring WHERE active AND next_date <= ?",
				UUID.class, Date.valueOf(latestTomorrow));
		int created = 0;
		for (UUID id : due) {
			try {
				Integer made = this.transaction.execute((status) -> runOne(id));
				created += (made != null) ? made : 0;
			}
			catch (RuntimeException ex) {
				logger.error("Recurring item {} failed: {}", id, ex.getMessage());
			}
		}
		return created;
	}

	/** Creates what is due for one item. @return how many transactions were created */
	int runOne(UUID id) {
		Row row = this.jdbc.query("SELECT " + COLUMNS + " FROM wallet_recurring WHERE id = ? FOR UPDATE",
				WalletRecurring::map, id).stream().findFirst().orElse(null);
		if (row == null || !row.active()) {
			return 0;
		}
		LocalDate today = today(row.userId());
		LocalDate next = row.nextDate();
		java.util.Set<LocalDate> skips = skips(id);
		int created = 0;
		for (int i = 0; i < MAX_CATCH_UP && !next.isAfter(today); i++) {
			if (skips.contains(next)) {
				next = nextOnOrAfter(row.startDate(), row.unit(), row.every(), next.plusDays(1));
				continue;
			}
			UUID txId = UUID.randomUUID();
			int inserted = this.jdbc.update("INSERT INTO wallet_transactions (id, user_id, type, amount, date, "
					+ "description, category_id, recurring_id, recurring_date, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
					+ "ON CONFLICT (recurring_id, recurring_date) WHERE recurring_id IS NOT NULL DO NOTHING", txId,
					row.userId(), row.type(), row.amount(), Date.valueOf(next), row.description(),
					row.categoryId(), row.id(), Date.valueOf(next), Timestamp.from(this.clock.instant()));
			if (inserted > 0) {
				created++;
				this.alerts.add(row.userId(), AlertService.RECURRING_CREATED, "Recurring item added",
						"\"" + row.name() + "\" was added to your wallet for " + next + ".", "/wallet/transactions/" + txId,
						"recurring:" + row.id() + ":" + next + ":created");
			}
			next = nextOnOrAfter(row.startDate(), row.unit(), row.every(), next.plusDays(1));
		}
		if (!next.equals(row.nextDate())) {
			this.jdbc.update("UPDATE wallet_recurring SET next_date = ? WHERE id = ?", Date.valueOf(next), id);
		}
		LocalDate coming = Recurrence.nextNotSkipped(row.startDate(), row.unit(), row.every(), next, null, skips);
		if (coming.equals(today.plusDays(1))) {
			next = coming;
			this.alerts.add(row.userId(), AlertService.RECURRING_DUE_TOMORROW, "Recurring item tomorrow",
					"\"" + row.name() + "\" will be added to your wallet tomorrow.", "/wallet/recurring",
					"recurring:" + row.id() + ":" + next + ":due");
		}
		if (created > 0) {
			this.budgets.checkAlerts(row.userId());
		}
		return created;
	}

	/** The first date of the series on or after {@code date} (see {@link Recurrence}). */
	static LocalDate nextOnOrAfter(LocalDate start, String unit, int every, LocalDate date) {
		return Recurrence.nextOnOrAfter(start, unit, every, date);
	}

	private LocalDate today(UUID userId) {
		return LocalDate.now(this.clock.withZone(this.zones.saved(userId).orElse(ZoneOffset.UTC)));
	}

	private Valid check(UUID userId, RecurringRequest request, Row existing) {
		BigDecimal amount = Money.parse("amount", request.amount());
		Recurrence.check(request.unit(), request.every());
		LocalDate start;
		try {
			start = LocalDate.parse(request.startDate());
		}
		catch (DateTimeParseException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error", "startDate: must be a real date");
		}
		WalletCategories.Category category = this.categories.find(userId, request.categoryId())
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"categoryId: unknown category"));
		if (!this.categories.isPickable(userId, category)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation_error",
					"categoryId: this category (or its parent) is archived");
		}
		boolean same = existing != null && existing.categoryId().equals(category.id())
				&& existing.type().equals(request.type());
		if (!same) {
			CategoryKinds.checkFits(category.kind(), request.type());
		}
		return new Valid(amount, start);
	}

	private Row mine(UUID userId, UUID id) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM wallet_recurring WHERE id = ? AND user_id = ?",
				WalletRecurring::map, id, userId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "recurring_not_found", "Recurring item not found"));
	}

	private Recurring one(UUID userId, UUID id) {
		return view(userId, mine(userId, id));
	}

	private Recurring view(UUID userId, Row row) {
		CategoryRef category = this.categories.find(userId, row.categoryId())
			.map((c) -> new CategoryRef(c.id(), c.name(), c.icon(), c.archived(), c.parentId(), c.kind()))
			.orElse(null);
		LocalDate today = today(userId);
		java.util.Set<LocalDate> skips = skips(row.id());
		LocalDate pausedUntil = (row.pausedUntil() != null && row.pausedUntil().isAfter(today)) ? row.pausedUntil()
				: null;
		LocalDate nextDate = Recurrence.nextNotSkipped(row.startDate(), row.unit(), row.every(), row.nextDate(), null,
				skips);
		java.util.Set<LocalDate> made = new java.util.HashSet<>(this.jdbc.queryForList(
				"SELECT recurring_date FROM wallet_transactions WHERE recurring_id = ? AND recurring_date >= ?",
				LocalDate.class, row.id(), Date.valueOf(today)));
		List<Recurrence.Upcoming> upcoming = Recurrence
			.dates(row.startDate(), row.unit(), row.every(), today, null, UPCOMING_COUNT)
			.stream()
			.map((date) -> new Recurrence.Upcoming(date, made.contains(date) ? "DONE" : skips.contains(date) ? "SKIPPED"
					: (!row.active() || (pausedUntil != null && date.isBefore(pausedUntil))) ? "PAUSED" : "UPCOMING"))
			.toList();
		return new Recurring(row.id(), row.name(), row.type(), Totals.money(row.amount()), category,
				row.description(), row.unit(), row.every(), row.startDate(), nextDate, row.active(), pausedUntil,
				upcoming);
	}

	private static Row map(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
				rs.getString("type"), rs.getBigDecimal("amount"),
				rs.getObject("category_id", UUID.class), rs.getString("description"), rs.getString("repeat_unit"),
				rs.getInt("repeat_every"),
				rs.getDate("start_date").toLocalDate(), rs.getDate("next_date").toLocalDate(), rs.getBoolean("active"),
				(rs.getDate("paused_until") != null) ? rs.getDate("paused_until").toLocalDate() : null);
	}

	private record Valid(BigDecimal amount, LocalDate start) {
	}

	private record Row(UUID id, UUID userId, String name, String type, BigDecimal amount,
			UUID categoryId, String description, String unit, int every, LocalDate startDate, LocalDate nextDate,
			boolean active, LocalDate pausedUntil) {
	}

	/**
	 * A recurring item as the API returns it.
	 * @param unit DAY, WEEK, MONTH or YEAR
	 * @param every every N units (MONTH/3 = every 3 months)
	 * @param nextDate the next date that will be made (skipped dates left out)
	 * @param active false = paused with no end (Resume to go on)
	 * @param pausedUntil paused until this date (then it goes on by itself), or null
	 * @param upcoming the next 6 dates from today with their status
	 */
	public record Recurring(UUID id, String name, String type, String amount, CategoryRef category,
			String description, String unit, int every, LocalDate startDate, LocalDate nextDate, boolean active,
			LocalDate pausedUntil, List<Recurrence.Upcoming> upcoming) {
	}

	/** Body of POST and PUT /api/wallet/recurring. Texts are trimmed before validation. */
	public record RecurringRequest(
			@NotBlank @Size(max = 60) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String name,
			@NotNull @Pattern(regexp = "EXPENSE|INCOME", message = "must be EXPENSE or INCOME") String type,
			@NotNull String amount,
			@NotNull UUID categoryId,
			@NotBlank @Size(max = 200) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String description,
			@NotNull @Pattern(regexp = Recurrence.UNITS, message = "must be DAY, WEEK, MONTH or YEAR")
			@io.swagger.v3.oas.annotations.media.Schema(allowableValues = { "DAY", "WEEK", "MONTH", "YEAR" }) String unit,
			@NotNull @io.swagger.v3.oas.annotations.media.Schema(description = "Every N units: DAY 1-365, WEEK 1-52, "
					+ "MONTH 1-24, YEAR 1-5", example = "1") Integer every,
			@NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31")
			String startDate) {

		public RecurringRequest {
			name = Names.clean(name);
			description = Names.clean(description);
		}

	}

}
