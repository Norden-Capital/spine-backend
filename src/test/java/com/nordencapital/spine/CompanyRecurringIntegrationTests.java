package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.recurring.CompanyRecurring;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Company repeat expenses: reminders only (never an automatic transaction), everyone reads
 * and adds, its creator or the OWNER manages, saving from a reminder marks it done, skip,
 * catch-up, pause.
 */
class CompanyRecurringIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private CompanyRecurring job;

	@Test
	void reminderThenSavedTransactionMarksItDone() throws Exception {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User other = activeUser("other@example.com", Role.MEMBER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");

		// The old admin paths stay OWNER-only (members use /api/recurring).
		assertThat(e.post("/api/admin/recurring", body("Rent", "MONTHLY", TODAY, null))).hasStatus(HttpStatus.FORBIDDEN);

		MvcTestResult created = b.post("/api/admin/recurring", body("Rent", "MONTHLY", TODAY, "www.Claude.ai/x"));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.description").isEqualTo("Rent");
		assertThat(created).bodyJson().extractingPath("$.website").isEqualTo("claude.ai");
		assertThat(created).bodyJson().extractingPath("$.amount").isEqualTo("1200.00");
		assertThat(created).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusMonths(1).toString());
		assertThat(created).bodyJson().extractingPath("$.dueCount").isEqualTo(1);
		assertThat(created).bodyJson().extractingPath("$.paused").isEqualTo(false);

		// Nothing is created automatically.
		assertThat(count("transactions")).isZero();
		assertThat(e.get("/api/recurring")).bodyJson().extractingPath("$[0].description").isEqualTo("Rent");

		// The reminder, for everyone to see; a COMPANY alert for everyone too.
		MvcTestResult due = e.get("/api/recurring/due");
		assertThat(due).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(due).bodyJson().extractingPath("$[0].status").isEqualTo("DUE");
		assertThat(due).bodyJson().extractingPath("$[0].overdue").isEqualTo(false);
		assertThat(due).bodyJson().extractingPath("$[0].dueDate").isEqualTo(TODAY.toString());
		assertThat(due).bodyJson().extractingPath("$[0].category.name").isEqualTo("Food");
		String occurrence = this.jdbc.queryForObject("SELECT id::text FROM company_recurring_occurrences", String.class);
		assertThat(alerts(boss)).isEqualTo(1);
		assertThat(alerts(other)).isEqualTo(1);
		assertThat(alerts(emp)).isEqualTo(1);
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items[0].scope").isEqualTo("COMPANY");
		assertThat(b.get("/api/alerts")).bodyJson().extractingPath("$.items[0].title").isEqualTo("Rent is due");
		assertThat(b.get("/api/alerts")).bodyJson().extractingPath("$.items[0].link")
			.isEqualTo("/recurring/due/" + occurrence);
		assertThat(b.get("/api/recurring/occurrences/" + occurrence)).bodyJson().extractingPath("$.amount")
			.isEqualTo("1200.00");

		// A member saves it from the reminder: DONE and linked; the owner's alert is read.
		assertThat(e.post("/api/transactions", tx(e, occurrence))).hasStatus(HttpStatus.CREATED);
		String txId = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		MvcTestResult done = b.get("/api/recurring/occurrences/" + occurrence);
		assertThat(done).bodyJson().extractingPath("$.status").isEqualTo("DONE");
		assertThat(done).bodyJson().extractingPath("$.transactionId").isEqualTo(txId);
		assertThat(b.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(unreadAlerts(boss)).isZero();
		assertThat(unreadAlerts(emp)).isZero();

		// Used twice: 409, and that second transaction is not saved.
		MvcTestResult again = b.post("/api/transactions", tx(b, occurrence));
		assertThat(again).hasStatus(HttpStatus.CONFLICT);
		assertThat(again).bodyJson().extractingPath("$.error").isEqualTo("occurrence_not_due");
		assertThat(count("transactions")).isEqualTo(1);

		// Delete: reminders go, the transaction stays.
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		assertThat(e.delete("/api/admin/recurring/" + id)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(b.delete("/api/admin/recurring/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(count("company_recurring_occurrences")).isZero();
		assertThat(count("transactions")).isEqualTo(1);
		assertThat(alerts(boss) + alerts(other) + alerts(emp)).isZero();
	}

	@Test
	void skipCatchUpPauseAndEndDate() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");

		// Started three weeks ago: the first reminder is today (past dates are not reminded).
		assertThat(b.post("/api/admin/recurring", noEarly(body("Google", "WEEKLY", TODAY.minusWeeks(3), null))))
			.hasStatus(HttpStatus.CREATED);
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		assertThat(count("company_recurring_occurrences")).isEqualTo(1);

		// Missed runs are caught up as overdue reminders (never as transactions).
		this.jdbc.update("UPDATE company_recurring SET next_date = ? WHERE id = ?::uuid",
				java.sql.Date.valueOf(TODAY.minusWeeks(2)), id);
		assertThat(this.job.runDue()).isEqualTo(2);
		assertThat(this.job.runDue()).isZero();
		MvcTestResult due = b.get("/api/recurring/due");
		assertThat(due).bodyJson().extractingPath("$.length()").isEqualTo(3);
		assertThat(due).bodyJson().extractingPath("$[0].overdue").isEqualTo(true);
		assertThat(due).bodyJson().extractingPath("$[2].overdue").isEqualTo(false);
		assertThat(count("transactions")).isZero();

		// Skip this time.
		String oldest = this.jdbc.queryForObject(
				"SELECT id::text FROM company_recurring_occurrences ORDER BY due_date LIMIT 1", String.class);
		assertThat(loggedIn("emp@example.com").post("/api/admin/recurring/occurrences/" + oldest + "/skip", ""))
			.hasStatus(HttpStatus.FORBIDDEN);
		MvcTestResult skipped = b.post("/api/admin/recurring/occurrences/" + oldest + "/skip", "");
		assertThat(skipped).hasStatus(HttpStatus.OK);
		assertThat(skipped).bodyJson().extractingPath("$.status").isEqualTo("SKIPPED");
		assertThat(b.post("/api/admin/recurring/occurrences/" + oldest + "/skip", "")).hasStatus(HttpStatus.CONFLICT);
		assertThat(b.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(2);

		// Paused: nothing new; resume does not remind about missed dates.
		assertThat(b.post("/api/admin/recurring/" + id + "/pause", "")).bodyJson().extractingPath("$.paused")
			.isEqualTo(true);
		this.jdbc.update("UPDATE company_recurring SET next_date = ? WHERE id = ?::uuid",
				java.sql.Date.valueOf(TODAY.minusWeeks(5)), id);
		assertThat(this.job.runDue()).isZero();
		MvcTestResult resumed = b.post("/api/admin/recurring/" + id + "/resume", "");
		assertThat(resumed).bodyJson().extractingPath("$.paused").isEqualTo(false);
		assertThat(resumed).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusWeeks(1).toString());
		assertThat(count("company_recurring_occurrences")).isEqualTo(3);

		// An end date stops the series.
		MvcTestResult ending = b.post("/api/admin/recurring", body("Trial", "MONTHLY", TODAY, TODAY.plusDays(5).toString(),
				null));
		assertThat(ending).bodyJson().extractingPath("$.nextDate").isNull();
		assertThat(b.post("/api/admin/recurring", body("Bad", "MONTHLY", TODAY, TODAY.minusDays(1).toString(), null)))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(b.post("/api/admin/recurring", body("Payroll", "BIWEEKLY", TODAY, null))).bodyJson()
			.extractingPath("$.nextDate")
			.isEqualTo(TODAY.plusWeeks(2).toString());
		assertThat(b.post("/api/admin/recurring", body("Bad", "DAILY", TODAY, null))).hasStatus(HttpStatus.BAD_REQUEST);
		MvcTestResult quarterly = b.post("/api/admin/recurring", body("Taxes", "QUARTERLY", TODAY, null));
		assertThat(quarterly).bodyJson().extractingPath("$.unit").isEqualTo("MONTH");
		assertThat(quarterly).bodyJson().extractingPath("$.every").isEqualTo(3);
		assertThat(quarterly).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusMonths(3).toString());
		assertThat(b.post("/api/admin/recurring", body("Insurance", "HALFYEARLY", TODAY, null))).bodyJson()
			.extractingPath("$.nextDate")
			.isEqualTo(TODAY.plusMonths(6).toString());
		String tooMany = body("Bad", "MONTHLY", TODAY, null).replace("\"every\":1", "\"every\":25");
		MvcTestResult refused = b.post("/api/admin/recurring", tooMany);
		assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(refused).bodyJson().extractingPath("$.message").isEqualTo("every: for MONTH must be 1 to 24");
		assertThat(b.post("/api/admin/recurring", body("Bad", "WEEKLY", TODAY, null).replace("\"every\":1",
				"\"every\":0"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void dueCountMatchesDueListAndPutClearsOptionalFields() {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");
		assertThat(b.post("/api/admin/recurring",
				noEarly(body("Rent", "MONTHLY", TODAY, TODAY.plusMonths(6).toString(), "claude.ai"))))
			.hasStatus(HttpStatus.CREATED);
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		// A reminder whose day has started somewhere on earth, but not yet in my time zone.
		this.jdbc.update("INSERT INTO company_recurring_occurrences (id, recurring_id, due_date, status, created_at) "
				+ "VALUES (gen_random_uuid(), ?::uuid, ?, 'DUE', now())", id, java.sql.Date.valueOf(TODAY.plusDays(1)));

		assertThat(b.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(b.get("/api/recurring")).bodyJson().extractingPath("$[0].dueCount").isEqualTo(1);
		assertThat(b.get("/api/recurring/" + id)).bodyJson().extractingPath("$.dueCount").isEqualTo(1);

		// PUT is a full replace: null clears endDate and website.
		MvcTestResult cleared = b.put("/api/admin/recurring/" + id, "{\"type\":\"EXPENSE\",\"amount\":\"1200\","
				+ "\"description\":\"Rent\",\"categoryId\":\"" + this.jdbc.queryForObject(
						"SELECT id::text FROM categories WHERE name = 'Food'", String.class)
				+ "\",\"unit\":\"MONTH\",\"every\":1,\"startDate\":\"" + TODAY + "\",\"endDate\":null,\"website\":null}");
		assertThat(cleared).hasStatus(HttpStatus.OK);
		assertThat(cleared).bodyJson().extractingPath("$.endDate").isNull();
		assertThat(cleared).bodyJson().extractingPath("$.website").isNull();
		assertThat(cleared).bodyJson().extractingPath("$.dueCount").isEqualTo(1);
	}

	@Test
	void membersAddAndManageTheirOwn() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		activeUser("other@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");
		Client o = loggedIn("other@example.com");

		// A member adds one (new path); the owner adds one too.
		MvcTestResult created = e.post("/api/recurring", noEarly(body("Coffee", "MONTHLY", TODAY.plusDays(3), null)));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		String mine = this.jdbc.queryForObject("SELECT id::text FROM company_recurring WHERE description = 'Coffee'",
				String.class);
		assertThat(b.post("/api/recurring", noEarly(body("Rent", "MONTHLY", TODAY.plusDays(5), null))))
			.hasStatus(HttpStatus.CREATED);
		String boss = this.jdbc.queryForObject("SELECT id::text FROM company_recurring WHERE description = 'Rent'",
				String.class);

		// canEdit: the creator and the owner; another member sees false.
		assertThat(e.get("/api/recurring/" + mine)).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		assertThat(e.get("/api/recurring/" + boss)).bodyJson().extractingPath("$.canEdit").isEqualTo(false);
		assertThat(o.get("/api/recurring/" + mine)).bodyJson().extractingPath("$.canEdit").isEqualTo(false);
		assertThat(b.get("/api/recurring/" + mine)).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		assertThat(b.get("/api/recurring")).bodyJson().extractingPath("$[0].canEdit").isEqualTo(true);
		assertThat(o.get("/api/recurring")).bodyJson().extractingPath("$[0].canEdit").isEqualTo(false);

		// The member changes, pauses, resumes and skips a date of their own.
		MvcTestResult changed = e.put("/api/recurring/" + mine, body("Coffee beans", "MONTHLY", TODAY.plusDays(3), null));
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(changed).bodyJson().extractingPath("$.description").isEqualTo("Coffee beans");
		assertThat(e.post("/api/recurring/" + mine + "/pause", "")).bodyJson().extractingPath("$.paused").isEqualTo(true);
		assertThat(e.post("/api/recurring/" + mine + "/resume", "")).bodyJson().extractingPath("$.paused")
			.isEqualTo(false);
		assertThat(e.post("/api/recurring/" + mine + "/skips", "{\"date\":\"" + TODAY.plusDays(3) + "\"}"))
			.hasStatus(HttpStatus.OK);
		assertThat(e.delete("/api/recurring/" + mine + "/skips/" + TODAY.plusDays(3))).hasStatus(HttpStatus.OK);

		// Not the owner's, and not another member's.
		String rent = body("Rent", "MONTHLY", TODAY.plusDays(5), null);
		assertThat(e.put("/api/recurring/" + boss, rent)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.put("/api/recurring/" + boss, rent)).bodyJson().extractingPath("$.error").isEqualTo("forbidden");
		assertThat(e.post("/api/recurring/" + boss + "/pause", "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/recurring/" + boss + "/pause-until", "{\"date\":\"" + TODAY.plusDays(9) + "\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/recurring/" + boss + "/skips", "{\"date\":\"" + TODAY.plusDays(5) + "\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.delete("/api/recurring/" + boss)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(o.put("/api/recurring/" + mine, body("Mine now", "MONTHLY", TODAY, null)))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(o.delete("/api/recurring/" + mine)).hasStatus(HttpStatus.FORBIDDEN);

		// "Skip this time": the repeat's creator or the owner.
		this.jdbc.update("INSERT INTO company_recurring_occurrences (id, recurring_id, due_date, status, created_at) "
				+ "VALUES (gen_random_uuid(), ?::uuid, ?, 'DUE', now())", mine, java.sql.Date.valueOf(TODAY));
		this.jdbc.update("INSERT INTO company_recurring_occurrences (id, recurring_id, due_date, status, created_at) "
				+ "VALUES (gen_random_uuid(), ?::uuid, ?, 'DUE', now())", boss, java.sql.Date.valueOf(TODAY));
		String mineDue = this.jdbc.queryForObject(
				"SELECT id::text FROM company_recurring_occurrences WHERE recurring_id = ?::uuid", String.class, mine);
		String bossDue = this.jdbc.queryForObject(
				"SELECT id::text FROM company_recurring_occurrences WHERE recurring_id = ?::uuid", String.class, boss);
		assertThat(e.get("/api/recurring/occurrences/" + mineDue)).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		assertThat(o.get("/api/recurring/occurrences/" + mineDue)).bodyJson().extractingPath("$.canEdit")
			.isEqualTo(false);
		assertThat(b.get("/api/recurring/occurrences/" + mineDue)).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		assertThat(e.get("/api/recurring/occurrences/" + bossDue)).bodyJson().extractingPath("$.canEdit")
			.isEqualTo(false);
		assertThat(o.post("/api/recurring/occurrences/" + mineDue + "/skip", "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/recurring/occurrences/" + bossDue + "/skip", "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/recurring/occurrences/" + mineDue + "/skip", "")).bodyJson().extractingPath("$.status")
			.isEqualTo("SKIPPED");

		// The owner manages the member's one, by the new path and by the old admin path.
		assertThat(b.put("/api/recurring/" + mine, body("Coffee (team)", "MONTHLY", TODAY.plusDays(3), null)))
			.bodyJson()
			.extractingPath("$.description")
			.isEqualTo("Coffee (team)");
		assertThat(b.post("/api/admin/recurring/" + mine + "/pause", "")).bodyJson().extractingPath("$.paused")
			.isEqualTo(true);
		assertThat(b.delete("/api/recurring/" + mine)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(e.delete("/api/recurring/" + mine)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(count("company_recurring")).isEqualTo(1);
	}

	@Test
	void remindDaysDefaultAndValidation() {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");
		// Existing repeats got [7, 3] from the migration (the column default).
		assertThat(this.jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE "
				+ "table_name = 'company_recurring' AND column_name = 'remind_days'", String.class)).contains("{7,3}");

		MvcTestResult created = b.post("/api/recurring", body("Rent", "MONTHLY", TODAY.plusDays(20), null));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.remindDays").asArray().containsExactly(7, 3);
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);

		// Only 7, 3 and 1.
		for (String bad : new String[] { "[5]", "[7,0]", "[7,null]", "[-1]" }) {
			MvcTestResult refused = b.post("/api/recurring", withRemind(body("Bad", "MONTHLY", TODAY, null), bad));
			assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(refused).bodyJson().extractingPath("$.message").isEqualTo("remindDays: each must be 7, 3 or 1");
			assertThat(b.put("/api/recurring/" + id, withRemind(body("Rent", "MONTHLY", TODAY.plusDays(20), null), bad)))
				.hasStatus(HttpStatus.BAD_REQUEST);
		}
		assertThat(count("company_recurring")).isEqualTo(1);

		// Any mix (biggest first, no repeats); empty is fine; a missing field keeps them.
		MvcTestResult changed = b.put("/api/recurring/" + id,
				withRemind(body("Rent", "MONTHLY", TODAY.plusDays(20), null), "[1,7,1]"));
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(changed).bodyJson().extractingPath("$.remindDays").asArray().containsExactly(7, 1);
		assertThat(b.put("/api/recurring/" + id, body("Rent", "MONTHLY", TODAY.plusDays(20), null))).bodyJson()
			.extractingPath("$.remindDays")
			.asArray()
			.containsExactly(7, 1);
		assertThat(b.put("/api/recurring/" + id, noEarly(body("Rent", "MONTHLY", TODAY.plusDays(20), null)))).bodyJson()
			.extractingPath("$.remindDays")
			.asArray()
			.isEmpty();
		assertThat(b.get("/api/recurring")).bodyJson().extractingPath("$[0].remindDays").asArray().isEmpty();
	}

	@Test
	void earlyRemindersAndAlerts() throws Exception {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");

		// Default [7, 3], due in 7 days: the reminder is made now, not overdue, in everyone's due list.
		MvcTestResult created = b.post("/api/recurring", body("Rent", "MONTHLY", TODAY.plusDays(7), null));
		assertThat(created).bodyJson().extractingPath("$.dueCount").isEqualTo(1);
		String rent = this.jdbc.queryForObject("SELECT id::text FROM company_recurring_occurrences", String.class);
		MvcTestResult due = e.get("/api/recurring/due");
		assertThat(due).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(due).bodyJson().extractingPath("$[0].id").isEqualTo(rent);
		assertThat(due).bodyJson().extractingPath("$[0].dueDate").isEqualTo(TODAY.plusDays(7).toString());
		assertThat(due).bodyJson().extractingPath("$[0].overdue").isEqualTo(false);
		assertThat(e.get("/api/recurring")).bodyJson().extractingPath("$[0].dueCount").isEqualTo(1);

		// The 7-day alert, for everyone (COMPANY), once.
		assertThat(alerts(boss)).isEqualTo(1);
		assertThat(alerts(emp)).isEqualTo(1);
		MvcTestResult alert = e.get("/api/alerts");
		assertThat(alert).bodyJson().extractingPath("$.items[0].scope").isEqualTo("COMPANY");
		assertThat(alert).bodyJson().extractingPath("$.items[0].title").isEqualTo("Rent is due in 7 days");
		assertThat(alert).bodyJson().extractingPath("$.items[0].body")
			.isEqualTo("$1200.00 on " + DAY.format(TODAY.plusDays(7)) + ". Tap to add it.");
		assertThat(alert).bodyJson().extractingPath("$.items[0].link").isEqualTo("/recurring/due/" + rent);
		this.job.runDue();
		assertThat(alerts(emp)).isEqualTo(1);

		// 3 days before: the 3-day alert. 1 day before (not chosen): nothing.
		moveTo(rent, TODAY.plusDays(3));
		this.job.runDue();
		assertThat(alerts(boss)).isEqualTo(2);
		assertThat(alerts(emp)).isEqualTo(2);
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items[0].title").isEqualTo("Rent is due in 3 days");
		moveTo(rent, TODAY.plusDays(1));
		this.job.runDue();
		assertThat(alerts(emp)).isEqualTo(2);

		// Skipped before its date: all its alerts are read, and no due-day alert comes.
		assertThat(b.post("/api/recurring/occurrences/" + rent + "/skip", "")).bodyJson().extractingPath("$.status")
			.isEqualTo("SKIPPED");
		assertThat(unreadAlerts(boss) + unreadAlerts(emp)).isZero();
		moveTo(rent, TODAY);
		this.job.runDue();
		assertThat(alerts(boss)).isEqualTo(2);
		assertThat(alerts(emp)).isEqualTo(2);

		// [3, 1], due in 3 days: made now, saved early (DONE): no "tomorrow" or due-day alert.
		assertThat(b.post("/api/recurring", withRemind(body("Cloud", "MONTHLY", TODAY.plusDays(3), null), "[1,3]")))
			.hasStatus(HttpStatus.CREATED);
		String cloud = occurrenceOf("Cloud");
		assertThat(alerts(emp)).isEqualTo(3);
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items[0].title").isEqualTo("Cloud is due in 3 days");
		assertThat(e.post("/api/transactions", tx(e, cloud))).hasStatus(HttpStatus.CREATED);
		assertThat(e.get("/api/recurring/occurrences/" + cloud)).bodyJson().extractingPath("$.status").isEqualTo("DONE");
		moveTo(cloud, TODAY.plusDays(1));
		this.job.runDue();
		moveTo(cloud, TODAY);
		this.job.runDue();
		assertThat(alerts(boss)).isEqualTo(3);
		assertThat(alerts(emp)).isEqualTo(3);

		// [1], due tomorrow: "is due tomorrow". [] due in 2 days: nothing yet.
		assertThat(b.post("/api/recurring", withRemind(body("Phone", "MONTHLY", TODAY.plusDays(1), null), "[1]")))
			.hasStatus(HttpStatus.CREATED);
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items[0].title").isEqualTo("Phone is due tomorrow");
		assertThat(b.post("/api/recurring", noEarly(body("Water", "MONTHLY", TODAY.plusDays(2), null))))
			.hasStatus(HttpStatus.CREATED);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM company_recurring_occurrences o JOIN company_recurring r "
				+ "ON r.id = o.recurring_id WHERE r.description = 'Water'", Integer.class)).isZero();

		// The due list: early ones with the overdue and today ones, by date.
		assertThat(b.post("/api/recurring", body("Taxes", "MONTHLY", TODAY.plusDays(5), null)))
			.hasStatus(HttpStatus.CREATED);
		assertThat(b.post("/api/recurring", noEarly(body("Google", "MONTHLY", TODAY, null)))).hasStatus(HttpStatus.CREATED);
		moveTo(occurrenceOf("Google"), TODAY.minusDays(2));
		MvcTestResult list = b.get("/api/recurring/due");
		assertThat(list).bodyJson().extractingPath("$.length()").isEqualTo(3);
		assertThat(list).bodyJson().extractingPath("$[0].description").isEqualTo("Google");
		assertThat(list).bodyJson().extractingPath("$[0].overdue").isEqualTo(true);
		assertThat(list).bodyJson().extractingPath("$[1].description").isEqualTo("Phone");
		assertThat(list).bodyJson().extractingPath("$[2].description").isEqualTo("Taxes");
		assertThat(list).bodyJson().extractingPath("$[2].overdue").isEqualTo(false);

		// Deleting a repeat removes its early alerts too.
		String phone = this.jdbc.queryForObject("SELECT id::text FROM company_recurring WHERE description = 'Phone'",
				String.class);
		int before = alerts(emp);
		assertThat(b.delete("/api/recurring/" + phone)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(alerts(emp)).isEqualTo(before - 1);
	}

	private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter
		.ofPattern("EEE d MMM", java.util.Locale.ENGLISH);

	private void moveTo(String occurrence, LocalDate date) {
		this.jdbc.update("UPDATE company_recurring_occurrences SET due_date = ? WHERE id = ?::uuid",
				java.sql.Date.valueOf(date), occurrence);
	}

	private String occurrenceOf(String description) {
		return this.jdbc.queryForObject("SELECT o.id::text FROM company_recurring_occurrences o JOIN company_recurring r "
				+ "ON r.id = o.recurring_id WHERE r.description = ?", String.class, description);
	}

	/** No early reminders (only on the due day, like before). */
	private static String noEarly(String json) {
		return withRemind(json, "[]");
	}

	private static String withRemind(String json, String days) {
		return json.substring(0, json.length() - 1) + ",\"remindDays\":" + days + "}";
	}

	private String body(String description, String frequency, LocalDate start, String website) {
		return body(description, frequency, start, null, website);
	}

	private String body(String description, String frequency, LocalDate start, String end, String website) {
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		return "{\"type\":\"EXPENSE\",\"amount\":\"1200\",\"description\":\"" + description
				+ "\",\"categoryId\":\"" + category + "\"," + repeatJson(frequency) + ",\"startDate\":\"" + start
				+ "\"" + ((end != null) ? ",\"endDate\":\"" + end + "\"" : "")
				+ ((website != null) ? ",\"website\":\"" + website + "\"" : "") + "}";
	}

	private String tx(Client client, String occurrence) {
		String file = FilesIntegrationTests.uploadedId(client.upload("rent.pdf", "application/pdf", TestFiles.PDF));
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		return "{\"type\":\"EXPENSE\",\"amount\":\"1200\",\"date\":\"" + TODAY
				+ "\",\"description\":\"Rent\",\"categoryId\":\"" + category + "\",\"fileIds\":[\"" + file
				+ "\"],\"confirmDuplicate\":true,\"recurringOccurrenceId\":\"" + occurrence + "\"}";
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private int alerts(User user) {
		return this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?",
				Integer.class, user.getId(), CompanyRecurring.ALERT_KIND);
	}

	private int unreadAlerts(User user) {
		return this.jdbc.queryForObject(
				"SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ? AND read_at IS NULL", Integer.class,
				user.getId(), CompanyRecurring.ALERT_KIND);
	}

}
