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
 * Company repeat expenses: reminders only (never an automatic transaction), the OWNER
 * manage, everyone reads, saving from a reminder marks it done, skip, catch-up, pause.
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

		// Employees read only.
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

		// The reminder, for everyone to see; alerts only for the OWNER.
		MvcTestResult due = e.get("/api/recurring/due");
		assertThat(due).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(due).bodyJson().extractingPath("$[0].status").isEqualTo("DUE");
		assertThat(due).bodyJson().extractingPath("$[0].overdue").isEqualTo(false);
		assertThat(due).bodyJson().extractingPath("$[0].dueDate").isEqualTo(TODAY.toString());
		assertThat(due).bodyJson().extractingPath("$[0].category.name").isEqualTo("Food");
		String occurrence = this.jdbc.queryForObject("SELECT id::text FROM company_recurring_occurrences", String.class);
		assertThat(alerts(boss)).isEqualTo(1);
		assertThat(alerts(other)).isZero();
		assertThat(alerts(emp)).isZero();
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
	}

	@Test
	void skipCatchUpPauseAndEndDate() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");

		// Started three weeks ago: the first reminder is today (past dates are not reminded).
		assertThat(b.post("/api/admin/recurring", body("Google", "WEEKLY", TODAY.minusWeeks(3), null)))
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
				body("Rent", "MONTHLY", TODAY, TODAY.plusMonths(6).toString(), "claude.ai"))).hasStatus(HttpStatus.CREATED);
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
