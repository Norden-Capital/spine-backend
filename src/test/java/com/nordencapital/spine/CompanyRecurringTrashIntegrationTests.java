package com.nordencapital.spine;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.recurring.CompanyRecurring;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Company repeat expenses: delete moves to the trash (hidden from the list, Due soon and the
 * job), only the OWNER sees the trash and restores, and every action is in the history (V38
 * backfills one CREATED entry for old repeats).
 */
class CompanyRecurringTrashIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private CompanyRecurring job;

	@Test
	void deleteMovesToTheTrashAndOnlyTheOwnerRestores() {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");

		// A member adds one (due today: a reminder and alerts) and deletes it.
		MvcTestResult created = e.post("/api/recurring", body("Rent", "MONTH", 1, TODAY, null, "[7,3]"));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		String occurrence = this.jdbc.queryForObject("SELECT id::text FROM company_recurring_occurrences", String.class);
		assertThat(b.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(alerts(boss) + alerts(emp)).isEqualTo(2);

		assertThat(e.delete("/api/recurring/" + id)).hasStatus(HttpStatus.NO_CONTENT);

		// Hidden from everything; the row stays (soft delete); alerts are gone.
		assertThat(count("company_recurring")).isEqualTo(1);
		for (Client c : List.of(b, e)) {
			assertThat(c.get("/api/recurring")).bodyJson().extractingPath("$.length()").isEqualTo(0);
			assertThat(c.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(0);
			assertThat(c.get("/api/recurring/" + id)).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(c.get("/api/recurring/occurrences/" + occurrence)).hasStatus(HttpStatus.NOT_FOUND);
		}
		assertThat(alerts(boss) + alerts(emp)).isZero();
		assertThat(e.put("/api/recurring/" + id, body("Rent", "MONTH", 1, TODAY, null, null)))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(e.post("/api/recurring/" + id + "/pause", "")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(e.delete("/api/recurring/" + id)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(e.post("/api/recurring/occurrences/" + occurrence + "/skip", "")).hasStatus(HttpStatus.NOT_FOUND);

		// The job skips it: no new reminders, no alerts.
		this.jdbc.update("UPDATE company_recurring SET next_date = ? WHERE id = ?::uuid",
				java.sql.Date.valueOf(TODAY.minusMonths(2)), id);
		assertThat(this.job.runDue()).isZero();
		assertThat(count("company_recurring_occurrences")).isEqualTo(1);
		assertThat(alerts(boss) + alerts(emp)).isZero();

		// A member cannot see the trash nor restore; nor its history while in the trash.
		assertThat(e.get("/api/admin/recurring/trash")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/admin/recurring/" + id + "/restore", "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.get("/api/recurring/" + id + "/history")).hasStatus(HttpStatus.NOT_FOUND);

		// The owner sees it, with who deleted it.
		MvcTestResult trash = b.get("/api/admin/recurring/trash");
		assertThat(trash).hasStatus(HttpStatus.OK);
		assertThat(trash).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(trash).bodyJson().extractingPath("$[0].id").isEqualTo(id);
		assertThat(trash).bodyJson().extractingPath("$[0].description").isEqualTo("Rent");
		assertThat(trash).bodyJson().extractingPath("$[0].amount").isEqualTo("1200.00");
		assertThat(trash).bodyJson().extractingPath("$[0].category.name").isEqualTo("Food");
		assertThat(trash).bodyJson().extractingPath("$[0].createdBy.id").isEqualTo(emp.getId().toString());
		assertThat(trash).bodyJson().extractingPath("$[0].deletedBy.id").isEqualTo(emp.getId().toString());
		assertThat(trash).bodyJson().extractingPath("$[0].deletedAt").isNotNull();
		assertThat(b.get("/api/recurring/" + id + "/history")).hasStatus(HttpStatus.OK);

		// Restore: back with all its settings; missed dates are not reminded (like Resume).
		MvcTestResult restored = b.post("/api/admin/recurring/" + id + "/restore", "");
		assertThat(restored).hasStatus(HttpStatus.OK);
		assertThat(restored).bodyJson().extractingPath("$.description").isEqualTo("Rent");
		assertThat(restored).bodyJson().extractingPath("$.remindDays").asArray().containsExactly(7, 3);
		assertThat(restored).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusMonths(1).toString());
		assertThat(count("company_recurring_occurrences")).isEqualTo(1);
		assertThat(b.get("/api/admin/recurring/trash")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(e.get("/api/recurring")).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(e.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(e.get("/api/recurring/occurrences/" + occurrence)).hasStatus(HttpStatus.OK);

		// Restoring again changes nothing; unknown id: 404.
		assertThat(b.post("/api/admin/recurring/" + id + "/restore", "")).hasStatus(HttpStatus.OK);
		assertThat(b.post("/api/admin/recurring/" + java.util.UUID.randomUUID() + "/restore", ""))
			.hasStatus(HttpStatus.NOT_FOUND);

		MvcTestResult history = e.get("/api/recurring/" + id + "/history");
		assertThat(history).hasStatus(HttpStatus.OK);
		assertThat(history).bodyJson().extractingPath("$.length()").isEqualTo(3);
		assertThat(history).bodyJson().extractingPath("$[0].action").isEqualTo("RESTORED");
		assertThat(history).bodyJson().extractingPath("$[0].by.id").isEqualTo(boss.getId().toString());
		assertThat(history).bodyJson().extractingPath("$[1].action").isEqualTo("DELETED");
		assertThat(history).bodyJson().extractingPath("$[1].by.id").isEqualTo(emp.getId().toString());
		assertThat(history).bodyJson().extractingPath("$[1].changes.length()").isEqualTo(0);
		assertThat(history).bodyJson().extractingPath("$[2].action").isEqualTo("CREATED");
		assertThat(history).bodyJson().extractingPath("$[2].by.id").isEqualTo(emp.getId().toString());
	}

	@Test
	void everyActionIsInTheHistory() {
		activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");
		LocalDate start = TODAY.plusDays(20);

		e.post("/api/recurring", body("Rent", "MONTH", 1, start, null, "[7,3]"));
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		String base = "/api/recurring/" + id;

		MvcTestResult created = e.get(base + "/history");
		assertThat(created).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(created).bodyJson().extractingPath("$[0].action").isEqualTo("CREATED");
		assertThat(created).bodyJson().extractingPath("$[0].by.id").isEqualTo(emp.getId().toString());
		assertThat(created).bodyJson().extractingPath("$[0].at").isNotNull();
		assertThat(created).bodyJson().extractingPath("$[0].changes[*].field").asArray()
			.containsExactly("type", "amount", "description", "category", "repeat", "startDate", "remindDays");
		assertThat(created).bodyJson().extractingPath("$[0].changes[?(@.field == 'repeat')].to").asArray()
			.containsExactly("Monthly");
		assertThat(created).bodyJson().extractingPath("$[0].changes[?(@.field == 'remindDays')].to").asArray()
			.containsExactly("7, 3 days before");
		assertThat(created).bodyJson().extractingPath("$[0].changes[0].from").isNull();

		// Edit (by the owner): only the changed fields; the same body again writes nothing.
		String edited = body("Office rent", "MONTH", 3, start, start.plusYears(1).toString(), "[1]")
			.replace("\"1200\"", "\"1300.5\"").replace("\"website\":null", "\"website\":\"aws.amazon.com\"");
		assertThat(b.put(base, edited)).hasStatus(HttpStatus.OK);
		assertThat(b.put(base, edited)).hasStatus(HttpStatus.OK);
		MvcTestResult updated = e.get(base + "/history");
		assertThat(updated).bodyJson().extractingPath("$.length()").isEqualTo(2);
		assertThat(updated).bodyJson().extractingPath("$[0].action").isEqualTo("UPDATED");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[*].field").asArray()
			.containsExactly("amount", "description", "website", "repeat", "endDate", "remindDays");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[0].from").isEqualTo("1200.00");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[0].to").isEqualTo("1300.50");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[1].from").isEqualTo("Rent");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[1].to").isEqualTo("Office rent");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[2].from").isNull();
		assertThat(updated).bodyJson().extractingPath("$[0].changes[2].to").isEqualTo("aws.amazon.com");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[3].to").isEqualTo("Every 3 months");
		assertThat(updated).bodyJson().extractingPath("$[0].changes[4].to").isEqualTo(start.plusYears(1).toString());
		assertThat(updated).bodyJson().extractingPath("$[0].changes[5].to").isEqualTo("1 day before");

		// Pause / resume (a second pause is no change), pause until, skip / unskip a date.
		assertThat(e.post(base + "/pause", "")).hasStatus(HttpStatus.OK);
		assertThat(e.post(base + "/pause", "")).hasStatus(HttpStatus.OK);
		assertThat(e.post(base + "/resume", "")).hasStatus(HttpStatus.OK);
		LocalDate until = TODAY.plusDays(40);
		assertThat(e.post(base + "/pause-until", "{\"date\":\"" + until + "\"}")).hasStatus(HttpStatus.OK);
		assertThat(e.post(base + "/resume", "")).hasStatus(HttpStatus.OK);
		assertThat(e.post(base + "/skips", "{\"date\":\"" + start.plusMonths(3) + "\"}")).hasStatus(HttpStatus.OK);
		assertThat(e.delete(base + "/skips/" + start.plusMonths(3))).hasStatus(HttpStatus.OK);
		assertThat(e.delete(base + "/skips/" + start.plusMonths(3))).hasStatus(HttpStatus.OK);

		MvcTestResult all = e.get(base + "/history");
		assertThat(all).bodyJson().extractingPath("$[*].action").asArray().containsExactly("UNSKIPPED", "SKIPPED",
				"RESUMED", "PAUSED_UNTIL", "RESUMED", "PAUSED", "UPDATED", "CREATED");
		assertThat(all).bodyJson().extractingPath("$[0].changes[0].field").isEqualTo("skipDate");
		assertThat(all).bodyJson().extractingPath("$[0].changes[0].from").isEqualTo(start.plusMonths(3).toString());
		assertThat(all).bodyJson().extractingPath("$[0].changes[0].to").isNull();
		assertThat(all).bodyJson().extractingPath("$[1].changes[0].to").isEqualTo(start.plusMonths(3).toString());
		assertThat(all).bodyJson().extractingPath("$[3].changes[0].field").isEqualTo("pausedUntil");
		assertThat(all).bodyJson().extractingPath("$[3].changes[0].to").isEqualTo(until.toString());

		// "Skip this time" on a waiting reminder is a SKIPPED date too.
		e.post("/api/recurring", body("Phone", "MONTH", 1, TODAY, null, "[]"));
		String phone = this.jdbc.queryForObject("SELECT id::text FROM company_recurring WHERE description = 'Phone'",
				String.class);
		String occurrence = this.jdbc.queryForObject("SELECT id::text FROM company_recurring_occurrences", String.class);
		assertThat(b.post("/api/recurring/occurrences/" + occurrence + "/skip", "")).hasStatus(HttpStatus.OK);
		MvcTestResult phoneHistory = e.get("/api/recurring/" + phone + "/history");
		assertThat(phoneHistory).bodyJson().extractingPath("$[0].action").isEqualTo("SKIPPED");
		assertThat(phoneHistory).bodyJson().extractingPath("$[0].changes[0].to").isEqualTo(TODAY.toString());
		assertThat(phoneHistory).bodyJson().extractingPath("$[1].changes[?(@.field == 'remindDays')].to").asArray()
			.containsExactly("Due day only");

		// Unknown repeat: 404.
		assertThat(e.get("/api/recurring/" + java.util.UUID.randomUUID() + "/history"))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void backfillWritesTheSameCreatedEntryAsTheApp() throws Exception {
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client e = loggedIn("emp@example.com");
		e.post("/api/recurring", body("Rent", "MONTH", 1, TODAY.plusDays(20), null, null));
		e.post("/api/recurring", body("Cloud", "MONTH", 3, TODAY.plusDays(20), TODAY.plusYears(2).toString(), "[1]")
			.replace("\"website\":null", "\"website\":\"aws.amazon.com\""));
		e.post("/api/recurring", body("Tax", "YEAR", 2, TODAY.plusDays(20), null, "[]"));
		e.post("/api/recurring", body("Daily", "DAY", 1, TODAY.plusDays(20), null, "[7,3,1]"));
		Map<String, String> byApp = changesByDescription();
		assertThat(byApp).hasSize(4);

		// Old repeats had no history: the V38 backfill writes one CREATED entry each.
		this.jdbc.update("DELETE FROM company_recurring_history");
		String script = new ClassPathResource("db/migration/V38__company_recurring_trash_and_history.sql")
			.getContentAsString(StandardCharsets.UTF_8);
		this.jdbc.execute(script.substring(script.indexOf("INSERT INTO company_recurring_history")));

		assertThat(changesByDescription()).isEqualTo(byApp);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM company_recurring_history h JOIN company_recurring r "
				+ "ON r.id = h.recurring_id WHERE h.action = 'CREATED' AND h.user_id = ? AND h.created_at = r.created_at",
				Integer.class, emp.getId())).isEqualTo(4);
	}

	// ---- helpers ----

	/** description → the CREATED changes (jsonb as text, so equal JSON compares equal). */
	private Map<String, String> changesByDescription() {
		Map<String, String> result = new java.util.HashMap<>();
		this.jdbc.query("SELECT r.description, h.changes::text AS changes FROM company_recurring_history h "
				+ "JOIN company_recurring r ON r.id = h.recurring_id WHERE h.action = 'CREATED'",
				(rs) -> {
					result.put(rs.getString("description"), rs.getString("changes"));
				});
		return result;
	}

	private String body(String description, String unit, int every, LocalDate start, String end, String remindDays) {
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		return "{\"type\":\"EXPENSE\",\"amount\":\"1200\",\"description\":\"" + description + "\",\"categoryId\":\""
				+ category + "\",\"unit\":\"" + unit + "\",\"every\":" + every + ",\"startDate\":\"" + start + "\""
				+ ((end != null) ? ",\"endDate\":\"" + end + "\"" : "") + ",\"website\":null"
				+ ((remindDays != null) ? ",\"remindDays\":" + remindDays : "") + "}";
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private int alerts(User user) {
		return this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?",
				Integer.class, user.getId(), CompanyRecurring.ALERT_KIND);
	}

}
