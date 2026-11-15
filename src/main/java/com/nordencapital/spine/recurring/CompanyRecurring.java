package com.nordencapital.spine.recurring;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

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
import com.nordencapital.spine.category.Category;
import com.nordencapital.spine.category.CategoryRepository;
import com.nordencapital.spine.category.CategoryService;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.CategoryKinds;
import com.nordencapital.spine.common.Money;
import com.nordencapital.spine.common.Names;
import com.nordencapital.spine.common.Recurrence;
import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.logo.LogoService;
import com.nordencapital.spine.me.UserZones;
import com.nordencapital.spine.transaction.TransactionResponse.CategoryRef;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

/**
 * Company repeat expenses. REMINDERS ONLY: nothing is created automatically (a company
 * transaction needs proof files). Each due date becomes one occurrence that stays DUE
 * (overdue after its date) until a transaction is saved from it (DONE, linked) or
 * its creator or the OWNER skips it (SKIPPED). Early reminders: each repeat has "remind days"
 * (any of 7, 3, 1 days before; new ones 7 and 3). The occurrence is made on the earliest of these
 * days (so it shows in "Due soon" and can be added early), and everyone in the company gets an
 * in-app alert on each of these days and on the due day (in their own time zone). Everyone can see the list, add a repeat expense and save a transaction from a
 * reminder, and change any repeat (user decision 2026-11-15).
 */
@Service
public class CompanyRecurring {

	public static final String ALERT_KIND = "COMPANY_RECURRING_DUE";

	/** How many next dates the API shows. */
	static final int UPCOMING_COUNT = 6;

	/** Safety stop for one item in one run. */
	private static final int MAX_CATCH_UP = 60;

	/** The latest time zone on earth: an occurrence exists as soon as its day starts anywhere. */
	private static final ZoneId LATEST_ZONE = ZoneOffset.ofHours(14);

	private static final String COLUMNS = "id, type, amount, description, category_id, website, repeat_unit, repeat_every, "
			+ "start_date, end_date, next_date, paused, paused_until, created_by, remind_days";

	/** The days before the due date that may be chosen for early alerts (biggest first). */
	static final List<Integer> REMIND_CHOICES = List.of(7, 3, 1);

	/** Remind days of new repeats (and of the ones that existed before early reminders). */
	static final List<Integer> DEFAULT_REMIND_DAYS = List.of(7, 3);

	/**
	 * SQL: how many days before its due date the occurrence {@code o} shows (its repeat's
	 * biggest remind day, 0 = only on the due day).
	 */
	private static final String LEAD = "(SELECT coalesce(max(d), 0) FROM company_recurring r, unnest(r.remind_days) d "
			+ "WHERE r.id = o.recurring_id)";

	/** Waiting reminders that show to someone whose today is the parameter. */
	private static final String SHOWN_DUE = "o.status = 'DUE' AND o.due_date - " + LEAD + " <= ?";

	private static final DateTimeFormatter ALERT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private static final Logger logger = LoggerFactory.getLogger(CompanyRecurring.class);

	private final JdbcTemplate jdbc;

	private final CategoryRepository categories;

	private final CategoryService categoryService;

	private final LogoService logos;

	private final AlertService alerts;

	private final Actors actors;

	private final UserZones zones;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public CompanyRecurring(JdbcTemplate jdbc, CategoryRepository categories, CategoryService categoryService,
			LogoService logos, AlertService alerts, Actors actors, UserZones zones, TransactionTemplate transaction,
			Clock clock) {
		this.jdbc = jdbc;
		this.categories = categories;
		this.categoryService = categoryService;
		this.logos = logos;
		this.alerts = alerts;
		this.actors = actors;
		this.zones = zones;
		this.transaction = transaction;
		this.clock = clock;
	}

	// ---- reading (everyone) ----

	/** All repeat expenses, next date first (finished ones last). */
	@Transactional(readOnly = true)
	public List<Recurring> list(AuthUser principal) {
		User user = this.actors.active(principal);
		List<Row> rows = this.jdbc.query("SELECT " + COLUMNS + " FROM company_recurring "
				+ "ORDER BY next_date NULLS LAST, description", CompanyRecurring::map);
		Map<UUID, Integer> due = new HashMap<>();
		this.jdbc.query("SELECT o.recurring_id, count(*) AS n FROM company_recurring_occurrences o WHERE " + SHOWN_DUE
				+ " GROUP BY o.recurring_id", (rs) -> {
					due.put(rs.getObject("recurring_id", UUID.class), rs.getInt("n"));
				}, Date.valueOf(today(user.getId())));
		Views views = views(rows, today(user.getId()));
		return rows.stream()
			.map((row) -> views.recurring(row, due.getOrDefault(row.id(), 0), canEdit(row, user)))
			.toList();
	}

	/** @throws ApiException 404 {@code recurring_not_found} */
	@Transactional(readOnly = true)
	public Recurring get(AuthUser principal, UUID id) {
		User user = this.actors.active(principal);
		return one(id, user);
	}

	/**
	 * Reminders still waiting: DUE occurrences whose date has come in my time zone, or will
	 * come within the repeat's biggest remind day (early reminders), oldest date first.
	 */
	@Transactional(readOnly = true)
	public List<Occurrence> due(AuthUser principal) {
		User user = this.actors.active(principal);
		LocalDate today = today(user.getId());
		return occurrences(SHOWN_DUE, List.of(Date.valueOf(today)), today, user);
	}

	/**
	 * One occurrence (any status), with the values to pre-fill the new transaction.
	 * @throws ApiException 404 {@code occurrence_not_found}
	 */
	@Transactional(readOnly = true)
	public Occurrence occurrence(AuthUser principal, UUID id) {
		User user = this.actors.active(principal);
		return occurrences("o.id = ?", List.of(id), today(user.getId()), user).stream()
			.findFirst()
			.orElseThrow(CompanyRecurring::occurrenceNotFound);
	}

	// ---- changes (everyone in the company adds and changes any repeat; user decision 2026-11-15) ----

	/** Every ACTIVE person may change, pause, skip or delete any repeat (callers are already ACTIVE). */
	@SuppressWarnings("unused")
	private static boolean canEdit(Row row, User user) {
		return user != null;
	}

	/**
	 * The caller (ACTIVE) and the repeat expense, when the caller may change it.
	 * @throws ApiException 404 {@code recurring_not_found}, 403 {@code forbidden}
	 */
	private Editable editable(AuthUser principal, UUID id) {
		User actor = this.actors.active(principal);
		Row row = row(id);
		if (!canEdit(row, actor)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
					"Only the person who added it or the owner can change it");
		}
		return new Editable(actor, row);
	}

	private record Editable(User actor, Row row) {
	}

	/** Any ACTIVE user. @throws ApiException 400 {@code validation_error} */
	@Transactional
	public Recurring create(AuthUser principal, RecurringRequest request) {
		User actor = this.actors.active(principal);
		Valid valid = check(request, null);
		List<Integer> remindDays = remindDays(request.remindDays(), DEFAULT_REMIND_DAYS);
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO company_recurring (id, type, amount, description, category_id, website, "
				+ "repeat_unit, repeat_every, start_date, end_date, next_date, paused, created_by, created_at, updated_at, "
				+ "remind_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?)", id, request.type(),
				valid.amount(), request.description(), request.categoryId(), valid.website(), request.unit(),
				request.every(), Date.valueOf(valid.start()), date(valid.end()),
				date(firstFrom(valid, today(actor.getId()))), actor.getId(), now, now, remindDays.toArray(Integer[]::new));
		this.logos.prefetch(valid.website());
		runOne(id);
		return one(id, actor);
	}

	/**
	 * Full replace (but a missing {@code remindDays} keeps them). The next reminder date is
	 * calculated again from my today; occurrences already made stay as they are.
	 * @throws ApiException 404 {@code recurring_not_found}, 400 {@code validation_error}
	 */
	@Transactional
	public Recurring update(AuthUser principal, UUID id, RecurringRequest request) {
		Editable editable = editable(principal, id);
		User actor = editable.actor();
		Row row = editable.row();
		Valid valid = check(request, row);
		List<Integer> remindDays = remindDays(request.remindDays(), row.remindDays());
		this.jdbc.update("UPDATE company_recurring SET type = ?, amount = ?, description = ?, "
				+ "category_id = ?, website = ?, repeat_unit = ?, repeat_every = ?, start_date = ?, end_date = ?, next_date = ?, "
				+ "remind_days = ?, updated_at = ? WHERE id = ?", request.type(), valid.amount(), request.description(),
				request.categoryId(), valid.website(), request.unit(), request.every(), Date.valueOf(valid.start()), date(valid.end()),
				date(firstFrom(valid, from(row, actor))), remindDays.toArray(Integer[]::new),
				Timestamp.from(this.clock.instant()), id);
		this.logos.prefetch(valid.website());
		runOne(id);
		return one(id, actor);
	}

	/**
	 * Pause or resume. Resume does not remind about the dates missed while paused.
	 * @throws ApiException 404 {@code recurring_not_found}
	 */
	@Transactional
	public Recurring setPaused(AuthUser principal, UUID id, boolean paused) {
		Editable editable = editable(principal, id);
		User actor = editable.actor();
		Row row = editable.row();
		boolean pausedUntil = row.pausedUntil() != null && row.pausedUntil().isAfter(today(actor.getId()));
		if (!paused && (row.paused() || pausedUntil)) {
			Valid valid = new Valid(row.amount(), row.startDate(), row.endDate(), row.website());
			this.jdbc.update("UPDATE company_recurring SET paused = false, paused_until = NULL, next_date = ?, "
					+ "updated_at = ? WHERE id = ?",
					date(firstFrom(valid, today(actor.getId()), row.unit(), row.every())), Timestamp.from(this.clock.instant()), id);
			runOne(id);
		}
		else if (paused && (!row.paused() || row.pausedUntil() != null)) {
			this.jdbc.update("UPDATE company_recurring SET paused = true, paused_until = NULL, updated_at = ? WHERE id = ?",
					Timestamp.from(this.clock.instant()), id);
		}
		return one(id, actor);
	}

	/**
	 * Pause until a date: no reminder before it; from that date the series goes on by
	 * itself. Reminders already due stay.
	 * @throws ApiException 404 {@code recurring_not_found}, 400 {@code validation_error}
	 * (not after my today)
	 */
	@Transactional
	public Recurring pauseUntil(AuthUser principal, UUID id, String dateText) {
		Editable editable = editable(principal, id);
		User actor = editable.actor();
		Row row = editable.row();
		LocalDate date = Recurrence.parseDate(dateText);
		if (!date.isAfter(today(actor.getId()))) {
			throw invalid("date: must be after today");
		}
		Valid valid = new Valid(row.amount(), row.startDate(), row.endDate(), row.website());
		this.jdbc.update("UPDATE company_recurring SET paused = false, paused_until = ?, next_date = ?, updated_at = ? "
				+ "WHERE id = ?", Date.valueOf(date), date(firstFrom(valid, date, row.unit(), row.every())),
				Timestamp.from(this.clock.instant()), id);
		return one(id, actor);
	}

	/**
	 * Skip one future date: no reminder for it; the next ones are normal. (A reminder that
	 * is already due is skipped with "skip this time" instead.)
	 * @throws ApiException 404 {@code recurring_not_found}, 400 {@code validation_error}
	 * (not a date of the series, past, or after the end), 409 {@code occurrence_exists}
	 */
	@Transactional
	public Recurring skip(AuthUser principal, UUID id, String dateText) {
		Editable editable = editable(principal, id);
		User actor = editable.actor();
		Row row = editable.row();
		LocalDate date = Recurrence.parseDate(dateText);
		if (!Recurrence.isSeriesDate(row.startDate(), row.unit(), row.every(), date)
				|| (row.endDate() != null && date.isAfter(row.endDate()))) {
			throw invalid("date: not a date of this series");
		}
		if (date.isBefore(today(actor.getId()))) {
			throw invalid("date: must be today or later");
		}
		Boolean exists = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM company_recurring_occurrences "
				+ "WHERE recurring_id = ? AND due_date = ?)", Boolean.class, id, Date.valueOf(date));
		if (Boolean.TRUE.equals(exists)) {
			throw new ApiException(HttpStatus.CONFLICT, "occurrence_exists",
					"This reminder is already there: use \"Skip this time\" on it");
		}
		this.jdbc.update("INSERT INTO company_recurring_skips (recurring_id, skip_date, created_by, created_at) "
				+ "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", id, Date.valueOf(date), actor.getId(),
				Timestamp.from(this.clock.instant()));
		return one(id, actor);
	}

	/**
	 * Undo a skip, before its date. If the job already went past that date (its early
	 * reminder day has come), the reminder is made now.
	 * @throws ApiException 404 {@code recurring_not_found}, 409 {@code too_late}
	 */
	@Transactional
	public Recurring unskip(AuthUser principal, UUID id, String dateText) {
		Editable editable = editable(principal, id);
		User actor = editable.actor();
		Row row = editable.row();
		LocalDate date = Recurrence.parseDate(dateText);
		if (!date.isAfter(today(actor.getId()))) {
			throw new ApiException(HttpStatus.CONFLICT, "too_late", "A skip can be undone only before its date");
		}
		int removed = this.jdbc.update("DELETE FROM company_recurring_skips WHERE recurring_id = ? AND skip_date = ?",
				id, Date.valueOf(date));
		boolean passed = row.nextDate() == null || row.nextDate().isAfter(date);
		boolean inPause = row.paused() || (row.pausedUntil() != null && date.isBefore(row.pausedUntil()));
		if (removed > 0 && passed && !inPause && !date.minusDays(row.lead()).isAfter(latestToday())) {
			this.jdbc.update("INSERT INTO company_recurring_occurrences (id, recurring_id, due_date, status, created_at) "
					+ "VALUES (?, ?, ?, 'DUE', ?) ON CONFLICT (recurring_id, due_date) DO NOTHING", UUID.randomUUID(), id,
					Date.valueOf(date), Timestamp.from(this.clock.instant()));
			sendAlerts();
		}
		return one(id, actor);
	}

	private java.util.Set<LocalDate> skips(UUID id) {
		return new java.util.HashSet<>(this.jdbc.queryForList(
				"SELECT skip_date FROM company_recurring_skips WHERE recurring_id = ?", LocalDate.class, id));
	}

	/** Dates are calculated from my today, or from the "paused until" date while it is in the future. */
	private LocalDate from(Row row, User actor) {
		LocalDate today = today(actor.getId());
		return (row.pausedUntil() != null && row.pausedUntil().isAfter(today)) ? row.pausedUntil() : today;
	}

	/**
	 * Deletes the repeat expense and its reminders. Transactions saved from it stay.
	 * @throws ApiException 404 {@code recurring_not_found}
	 */
	@Transactional
	public void delete(AuthUser principal, UUID id) {
		editable(principal, id);
		// Its reminders go with it, so their alerts go too (their links would be dead).
		this.jdbc.update("DELETE FROM notifications n USING company_recurring_occurrences o WHERE o.recurring_id = ? "
				+ "AND n.dedupe_key LIKE 'company-recurring:' || o.id || ':%'", id);
		this.jdbc.update("DELETE FROM company_recurring WHERE id = ?", id);
	}

	/**
	 * "Skip this time": the occurrence is closed without a transaction. Its repeat's creator
	 * or the OWNER.
	 * @throws ApiException 404 {@code occurrence_not_found}, 409 {@code occurrence_not_due},
	 * 403 {@code forbidden}
	 */
	@Transactional
	public Occurrence skip(AuthUser principal, UUID occurrenceId) {
		User actor = this.actors.active(principal);
		lockDue(occurrenceId);
		UUID recurringId = this.jdbc.queryForObject(
				"SELECT recurring_id FROM company_recurring_occurrences WHERE id = ?", UUID.class, occurrenceId);
		if (!canEdit(row(recurringId), actor)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
					"Only the person who added it or the owner can skip it");
		}
		this.jdbc.update("UPDATE company_recurring_occurrences SET status = 'SKIPPED', done_by = ?, done_at = ? "
				+ "WHERE id = ?", actor.getId(), Timestamp.from(this.clock.instant()), occurrenceId);
		markAlertsRead(occurrenceId);
		return occurrences("o.id = ?", List.of(occurrenceId), today(actor.getId()), actor).get(0);
	}

	/**
	 * Called when a company transaction is saved from a reminder (same database
	 * transaction): the occurrence becomes DONE and is linked to it.
	 * @throws ApiException 404
	 * {@code occurrence_not_found}, 409 {@code occurrence_not_due}
	 */
	public void markDone(User actor, UUID occurrenceId, UUID transactionId) {
		lockDue(occurrenceId);
		this.jdbc.update("UPDATE company_recurring_occurrences SET status = 'DONE', transaction_id = ?, done_by = ?, "
				+ "done_at = ? WHERE id = ?", transactionId, actor.getId(), Timestamp.from(this.clock.instant()),
				occurrenceId);
		markAlertsRead(occurrenceId);
	}

	// ---- the job ----

	/**
	 * Every hour: makes the occurrences whose date (or first early reminder day) has come
	 * (nothing else is created), then alerts everyone in the company on each remind day and
	 * on the due day, in their own time zone.
	 * @return how many occurrences were made
	 */
	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
	public int runDue() {
		List<UUID> due = this.jdbc.queryForList(
				"SELECT id FROM company_recurring WHERE NOT paused AND next_date IS NOT NULL AND next_date - "
						+ "coalesce((SELECT max(d) FROM unnest(remind_days) d), 0) <= ?",
				UUID.class, Date.valueOf(latestToday()));
		int made = 0;
		for (UUID id : due) {
			try {
				Integer count = this.transaction.execute((status) -> runOne(id));
				made += (count != null) ? count : 0;
			}
			catch (RuntimeException ex) {
				logger.error("Company repeat expense {} failed: {}", id, ex.getMessage());
			}
		}
		try {
			this.transaction.executeWithoutResult((status) -> sendAlerts());
		}
		catch (RuntimeException ex) {
			logger.error("Company repeat expense alerts failed: {}", ex.getMessage());
		}
		return made;
	}

	/**
	 * Makes the occurrences of one item up to today (latest zone) plus its biggest remind day.
	 * @return how many
	 */
	int runOne(UUID id) {
		Row row = this.jdbc.query("SELECT " + COLUMNS + " FROM company_recurring WHERE id = ? FOR UPDATE",
				CompanyRecurring::map, id).stream().findFirst().orElse(null);
		if (row == null || row.paused() || row.nextDate() == null) {
			return 0;
		}
		LocalDate latest = latestToday();
		LocalDate next = row.nextDate();
		int made = 0;
		java.util.Set<LocalDate> skipped = skips(id);
		for (int i = 0; i < MAX_CATCH_UP && next != null && !next.minusDays(row.lead()).isAfter(latest); i++) {
			made += skipped.contains(next) ? 0 : this.jdbc.update("INSERT INTO company_recurring_occurrences (id, recurring_id, due_date, status, "
					+ "created_at) VALUES (?, ?, ?, 'DUE', ?) ON CONFLICT (recurring_id, due_date) DO NOTHING",
					UUID.randomUUID(), id, Date.valueOf(next), Timestamp.from(this.clock.instant()));
			next = Recurrence.nextOnOrAfter(row.startDate(), row.unit(), row.every(), next.plusDays(1));
			if (row.endDate() != null && next.isAfter(row.endDate())) {
				next = null;
			}
		}
		if (!Objects.equals(next, row.nextDate())) {
			this.jdbc.update("UPDATE company_recurring SET next_date = ? WHERE id = ?", date(next), id);
		}
		if (made > 0) {
			sendAlerts();
		}
		return made;
	}

	/**
	 * COMPANY alerts for everyone in the company, per DUE occurrence: one on each remind day
	 * before its date (exactly N days before, in their time zone) and one once its day has come.
	 * A DONE or SKIPPED occurrence gets no more alerts.
	 */
	void sendAlerts() {
		List<UUID> managers = this.alerts.companyUsers();
		if (managers.isEmpty()) {
			return;
		}
		List<Occurrence> due = occurrences(SHOWN_DUE, List.of(Date.valueOf(latestToday())), latestToday(), null);
		if (due.isEmpty()) {
			return;
		}
		Map<UUID, List<Integer>> remindDays = new HashMap<>();
		this.jdbc.query("SELECT " + COLUMNS + " FROM company_recurring WHERE id = ANY (?)", CompanyRecurring::map,
				(Object) due.stream().map(Occurrence::recurringId).distinct().toArray(UUID[]::new))
			.forEach((row) -> remindDays.put(row.id(), row.remindDays()));
		for (UUID manager : managers) {
			LocalDate today = today(manager);
			for (Occurrence occurrence : due) {
				String link = "/recurring/due/" + occurrence.id();
				if (!occurrence.dueDate().isAfter(today)) {
					this.alerts.add(manager, AlertService.COMPANY, ALERT_KIND, title(occurrence.description()),
							"$" + occurrence.amount() + " · due " + occurrence.dueDate()
									+ ". Tap to add it with its proof.",
							link, alertKey(occurrence.id()));
					continue;
				}
				int days = (int) ChronoUnit.DAYS.between(today, occurrence.dueDate());
				if (remindDays.getOrDefault(occurrence.recurringId(), List.of()).contains(days)) {
					this.alerts.add(manager, AlertService.COMPANY, ALERT_KIND, earlyTitle(occurrence.description(), days),
							"$" + occurrence.amount() + " on " + ALERT_DATE.format(occurrence.dueDate())
									+ ". Tap to add it.",
							link, earlyAlertKey(occurrence.id(), days));
				}
			}
		}
	}

	// ---- helpers ----

	private static String title(String description) {
		return shorten(description + " is due");
	}

	private static String earlyTitle(String description, int days) {
		return shorten(description + ((days == 1) ? " is due tomorrow" : " is due in " + days + " days"));
	}

	private static String shorten(String title) {
		return (title.length() > 120) ? title.substring(0, 117) + "..." : title;
	}

	private static String alertKey(UUID occurrenceId) {
		return "company-recurring:" + occurrenceId + ":due";
	}

	/** One per occurrence and remind day (same prefix as {@link #alertKey}). */
	private static String earlyAlertKey(UUID occurrenceId, int days) {
		return "company-recurring:" + occurrenceId + ":before:" + days;
	}

	/** The reminder is handled: all its alerts (early ones too) are marked read for everyone. */
	private void markAlertsRead(UUID occurrenceId) {
		this.jdbc.update("UPDATE notifications SET read_at = coalesce(read_at, ?) WHERE dedupe_key LIKE ?",
				Timestamp.from(this.clock.instant()), "company-recurring:" + occurrenceId + ":%");
	}

	/**
	 * The remind days asked for, biggest first without repeats; null = {@code fallback}.
	 * @throws ApiException 400 {@code validation_error} (a value other than 7, 3 or 1)
	 */
	static List<Integer> remindDays(List<Integer> asked, List<Integer> fallback) {
		if (asked == null) {
			return fallback;
		}
		for (Integer day : asked) {
			if (day == null || !REMIND_CHOICES.contains(day)) {
				throw invalid("remindDays: each must be 7, 3 or 1");
			}
		}
		return REMIND_CHOICES.stream().filter(asked::contains).toList();
	}

	private void lockDue(UUID occurrenceId) {
		String status = this.jdbc.query("SELECT status FROM company_recurring_occurrences WHERE id = ? FOR UPDATE",
				(rs, row) -> rs.getString("status"), occurrenceId).stream().findFirst()
			.orElseThrow(CompanyRecurring::occurrenceNotFound);
		if (!"DUE".equals(status)) {
			throw new ApiException(HttpStatus.CONFLICT, "occurrence_not_due",
					"This reminder was already handled (" + status.toLowerCase() + ")");
		}
	}

	private LocalDate today(UUID userId) {
		return LocalDate.now(this.clock.withZone(this.zones.saved(userId).orElse(ZoneOffset.UTC)));
	}

	private LocalDate latestToday() {
		return LocalDate.now(this.clock.withZone(LATEST_ZONE));
	}

	/** First reminder date on or after {@code from}, or null if past the end date. */
	private static LocalDate firstFrom(Valid valid, LocalDate from, String unit, int every) {
		LocalDate first = Recurrence.nextOnOrAfter(valid.start(), unit, every, from);
		return (valid.end() != null && first.isAfter(valid.end())) ? null : first;
	}

	private LocalDate firstFrom(Valid valid, LocalDate from) {
		return firstFrom(valid, from, valid.unit(), valid.every());
	}

	private static Date date(LocalDate date) {
		return (date != null) ? Date.valueOf(date) : null;
	}

	private Valid check(RecurringRequest request, Row existing) {
		BigDecimal amount = Money.parse("amount", request.amount());
		LocalDate start = parse("startDate", request.startDate());
		LocalDate end = (request.endDate() != null && !request.endDate().isBlank()) ? parse("endDate", request.endDate())
				: null;
		if (end != null && end.isBefore(start)) {
			throw invalid("endDate: must not be before startDate");
		}
		Category category = this.categories.findById(request.categoryId())
			.orElseThrow(() -> invalid("categoryId: unknown category"));
		boolean keepsOld = existing != null && existing.categoryId().equals(category.getId());
		if (!keepsOld && !this.categoryService.isPickable(category)) {
			throw invalid("categoryId: this category (or its parent) is archived");
		}
		if (!keepsOld || !existing.type().equals(request.type())) {
			CategoryKinds.checkFits(category.getKind(), request.type());
		}
		Recurrence.check(request.unit(), request.every());
		Valid valid = new Valid(amount, start, end, Websites.normalize(request.website()));
		return valid.withRepeat(request.unit(), request.every());
	}

	private static LocalDate parse(String field, String value) {
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw invalid(field + ": must be a real date like 2026-03-31");
		}
	}

	private Row row(UUID id) {
		return this.jdbc.query("SELECT " + COLUMNS + " FROM company_recurring WHERE id = ?", CompanyRecurring::map, id)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "recurring_not_found",
					"Repeat expense not found"));
	}

	/** dueCount = the same reminders GET /api/recurring/due shows to this user (early ones too). */
	private Recurring one(UUID id, User viewer) {
		Row row = row(id);
		Integer due = this.jdbc.queryForObject("SELECT count(*) FROM company_recurring_occurrences o WHERE "
				+ "o.recurring_id = ? AND " + SHOWN_DUE, Integer.class, id, Date.valueOf(today(viewer.getId())));
		return views(List.of(row), today(viewer.getId())).recurring(row, (due != null) ? due : 0,
				canEdit(row, viewer));
	}

	/** @param viewer for {@code canEdit}; null = false */
	private List<Occurrence> occurrences(String where, List<Object> args, LocalDate today, User viewer) {
		List<OccurrenceRow> found = this.jdbc.query("SELECT o.id, o.recurring_id, o.due_date, o.status, "
				+ "o.transaction_id FROM company_recurring_occurrences o WHERE " + where
				+ " ORDER BY o.due_date, o.id", (rs, n) -> new OccurrenceRow(rs.getObject("id", UUID.class),
						rs.getObject("recurring_id", UUID.class), rs.getDate("due_date").toLocalDate(),
						rs.getString("status"), rs.getObject("transaction_id", UUID.class)),
				args.toArray());
		if (found.isEmpty()) {
			return List.of();
		}
		Map<UUID, Row> rows = new HashMap<>();
		this.jdbc.query("SELECT " + COLUMNS + " FROM company_recurring WHERE id = ANY (?)", CompanyRecurring::map,
				(Object) found.stream().map(OccurrenceRow::recurringId).distinct().toArray(UUID[]::new))
			.forEach((row) -> rows.put(row.id(), row));
		Views views = views(new ArrayList<>(rows.values()), today);
		return found.stream().map((o) -> {
			Row row = rows.get(o.recurringId());
			return new Occurrence(o.id(), o.recurringId(), o.dueDate(), o.status(),
					"DUE".equals(o.status()) && o.dueDate().isBefore(today), o.transactionId(), row.type(),
					Totals.money(row.amount()), row.description(), views.category(row),
					row.website(), views.logoUrls(row), viewer != null && canEdit(row, viewer));
		}).toList();
	}

	/** Categories (with parents) and logos for these rows, loaded at once. */
	private Views views(List<Row> rows, LocalDate today) {
		Map<UUID, Category> byId = new HashMap<>();
		this.categories.findAllById(rows.stream().map(Row::categoryId).distinct().toList())
			.forEach((c) -> byId.put(c.getId(), c));
		this.categories.findAllById(byId.values().stream().map(Category::getParentId).filter(Objects::nonNull).toList())
			.forEach((c) -> byId.put(c.getId(), c));
		List<String> domains = new ArrayList<>();
		rows.forEach((row) -> domains.add(row.website()));
		byId.values().forEach((c) -> domains.add(c.getWebsite()));
		domains.removeIf(Objects::isNull);
		Map<UUID, java.util.Set<LocalDate>> skips = new HashMap<>();
		Map<UUID, Map<LocalDate, String>> made = new HashMap<>();
		UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
		if (ids.length > 0) {
			this.jdbc.query("SELECT recurring_id, skip_date FROM company_recurring_skips WHERE recurring_id = ANY (?)",
					(rs) -> {
						skips.computeIfAbsent(rs.getObject("recurring_id", UUID.class), (k) -> new java.util.HashSet<>())
							.add(rs.getDate("skip_date").toLocalDate());
					}, (Object) ids);
			this.jdbc.query("SELECT recurring_id, due_date, status FROM company_recurring_occurrences "
					+ "WHERE recurring_id = ANY (?) AND due_date >= ?", (rs) -> {
						made.computeIfAbsent(rs.getObject("recurring_id", UUID.class), (k) -> new HashMap<>())
							.put(rs.getDate("due_date").toLocalDate(), rs.getString("status"));
					}, ids, Date.valueOf(today));
		}
		return new Views(byId, this.logos.logoUrls(domains), skips, made, today);
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	private static ApiException occurrenceNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "occurrence_not_found", "Reminder not found");
	}

	private static Row map(ResultSet rs, int rowNum) throws SQLException {
		Date end = rs.getDate("end_date");
		Date next = rs.getDate("next_date");
		return new Row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getBigDecimal("amount"),
				rs.getString("description"), rs.getObject("category_id", UUID.class),
				rs.getString("website"), rs.getString("repeat_unit"), rs.getInt("repeat_every"),
				rs.getDate("start_date").toLocalDate(),
				(end != null) ? end.toLocalDate() : null, (next != null) ? next.toLocalDate() : null,
				rs.getBoolean("paused"),
				(rs.getDate("paused_until") != null) ? rs.getDate("paused_until").toLocalDate() : null,
				rs.getObject("created_by", UUID.class), remindDays(rs.getArray("remind_days")));
	}

	private static List<Integer> remindDays(java.sql.Array array) throws SQLException {
		List<Integer> days = new ArrayList<>();
		for (Object day : (Object[]) array.getArray()) {
			days.add(((Number) day).intValue());
		}
		return REMIND_CHOICES.stream().filter(days::contains).toList();
	}

	private record Views(Map<UUID, Category> categories, Map<String, List<String>> logoUrls,
			Map<UUID, java.util.Set<LocalDate>> skips, Map<UUID, Map<LocalDate, String>> occurrences, LocalDate today) {

		CategoryRef category(Row row) {
			Category c = this.categories.get(row.categoryId());
			return (c != null) ? new CategoryRef(c.getId(), c.getName(), c.getIcon(), c.isArchived(), c.getParentId(),
					c.getKind())
					: null;
		}

		/** Own website logo, then the category's, then the parent's (like transactions). */
		List<String> logoUrls(Row row) {
			Category c = this.categories.get(row.categoryId());
			Category parent = (c != null && c.getParentId() != null) ? this.categories.get(c.getParentId()) : null;
			return LogoService.chain(this.logoUrls, row.website(), (c != null) ? c.getWebsite() : null,
					(parent != null) ? parent.getWebsite() : null);
		}

		Recurring recurring(Row row, int dueCount, boolean canEdit) {
			java.util.Set<LocalDate> skipped = this.skips.getOrDefault(row.id(), java.util.Set.of());
			Map<LocalDate, String> made = this.occurrences.getOrDefault(row.id(), Map.of());
			LocalDate pausedUntil = (row.pausedUntil() != null && row.pausedUntil().isAfter(this.today))
					? row.pausedUntil() : null;
			LocalDate nextDate = (row.nextDate() != null) ? Recurrence.nextNotSkipped(row.startDate(), row.unit(),
					row.every(), row.nextDate(), row.endDate(), skipped) : null;
			List<Recurrence.Upcoming> upcoming = Recurrence
				.dates(row.startDate(), row.unit(), row.every(), this.today, row.endDate(), UPCOMING_COUNT)
				.stream()
				.map((date) -> new Recurrence.Upcoming(date, made.containsKey(date) ? made.get(date)
						: skipped.contains(date) ? "SKIPPED"
								: (row.paused() || (pausedUntil != null && date.isBefore(pausedUntil))) ? "PAUSED"
										: "UPCOMING"))
				.toList();
			return new Recurring(row.id(), row.type(), Totals.money(row.amount()), row.description(),
					category(row), row.website(), logoUrls(row), row.unit(), row.every(), row.startDate(), row.endDate(),
					nextDate, row.paused(), pausedUntil, dueCount, upcoming, row.remindDays(), canEdit);
		}

	}

	private record Valid(BigDecimal amount, LocalDate start, LocalDate end, String website, String unit, int every) {

		Valid(BigDecimal amount, LocalDate start, LocalDate end, String website) {
			this(amount, start, end, website, null, 0);
		}

		Valid withRepeat(String unit, int every) {
			return new Valid(this.amount, this.start, this.end, this.website, unit, every);
		}

	}

	private record Row(UUID id, String type, BigDecimal amount, String description, UUID categoryId,
			String website, String unit, int every, LocalDate startDate, LocalDate endDate, LocalDate nextDate,
			boolean paused, LocalDate pausedUntil, UUID createdBy, List<Integer> remindDays) {

		/** How many days before its date an occurrence is made (0 = on the day). */
		int lead() {
			return this.remindDays.isEmpty() ? 0 : this.remindDays.get(0);
		}

	}

	private record OccurrenceRow(UUID id, UUID recurringId, LocalDate dueDate, String status, UUID transactionId) {
	}

	/**
	 * A repeat expense as the API returns it.
	 * @param unit DAY, WEEK, MONTH or YEAR
	 * @param every every N units (MONTH/3 = every 3 months)
	 * @param nextDate the next reminder date, skipped dates left out (null = finished after endDate)
	 * @param paused true = paused with no end (Resume to go on)
	 * @param pausedUntil paused until this date (then it goes on by itself), or null
	 * @param dueCount reminders waiting for me: DUE and their date (or their first early reminder
	 * day) has come in my time zone (same as GET /api/recurring/due)
	 * @param upcoming the next 6 dates from my today: UPCOMING, SKIPPED, PAUSED, or the reminder
	 * status DUE / DONE / SKIPPED once it exists
	 * @param remindDays early alerts: days before the date (7, 3, 1; biggest first; empty = only
	 * on the due day)
	 * @param canEdit true when I may change, pause, skip or delete it (everyone in the company)
	 */
	public record Recurring(UUID id, String type, String amount, String description,
			CategoryRef category, String website, List<String> logoUrls, String unit, int every, LocalDate startDate,
			LocalDate endDate, LocalDate nextDate, boolean paused, LocalDate pausedUntil, int dueCount,
			List<Recurrence.Upcoming> upcoming, List<Integer> remindDays, boolean canEdit) {
	}

	/**
	 * One reminder, with the values to pre-fill the new transaction.
	 * @param status DUE, DONE or SKIPPED
	 * @param overdue DUE and its date is before my today (an early reminder is not overdue)
	 * @param transactionId the transaction saved from it (DONE), else null
	 * @param canEdit true when I may skip it (everyone in the company)
	 */
	public record Occurrence(UUID id, UUID recurringId, LocalDate dueDate, String status, boolean overdue,
			UUID transactionId, String type, String amount, String description, CategoryRef category,
			String website, List<String> logoUrls, boolean canEdit) {
	}

	/** Body of POST and PUT /api/recurring (and /api/admin/recurring). The description is trimmed. */
	public record RecurringRequest(
			@NotNull @Pattern(regexp = "EXPENSE|INCOME", message = "must be EXPENSE or INCOME") String type,
			@NotNull String amount,
			@NotBlank @Size(max = 200) @Pattern(regexp = Names.NO_CONTROL_CHARS,
					message = "must not contain control characters") String description,
			@NotNull UUID categoryId,
			@Schema(nullable = true) @Size(max = Websites.MAX_INPUT) String website,
			@NotNull @Pattern(regexp = Recurrence.UNITS, message = "must be DAY, WEEK, MONTH or YEAR")
			@io.swagger.v3.oas.annotations.media.Schema(allowableValues = { "DAY", "WEEK", "MONTH", "YEAR" }) String unit,
			@NotNull @io.swagger.v3.oas.annotations.media.Schema(description = "Every N units: DAY 1-365, WEEK 1-52, "
					+ "MONTH 1-24, YEAR 1-5", example = "1") Integer every,
			@NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31")
			String startDate,
			@Schema(nullable = true, description = "Optional last date (included)")
			@Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}", message = "must be like 2026-03-31") String endDate,
			@Schema(nullable = true, description = "Early alerts: days before the date, any of 7, 3, 1 (empty = only "
					+ "on the due day). Missing: [7, 3] on create, unchanged on update", example = "[7, 3]")
			List<Integer> remindDays) {

		public RecurringRequest {
			description = (description != null) ? description.strip() : null;
		}

	}

}
