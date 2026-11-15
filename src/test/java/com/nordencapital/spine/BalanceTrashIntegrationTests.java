package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.balance.BalanceService;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Balances: anyone deletes (moves to the trash: hidden from the list, the dashboard, the job
 * and the alerts), only the OWNER sees the trash and restores, and the history gets DELETED
 * and RESTORED lines (the guess goes on across them).
 */
class BalanceTrashIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Casablanca"));

	@Autowired
	private BalanceService job;

	@Test
	void deleteMovesToTheTrashAndOnlyTheOwnerRestores() {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");

		// A member adds two; Twilio started 6 days ago: runs out in 7 days (an alert for everyone).
		assertThat(e.post("/api/balances", body("Twilio"))).hasStatus(HttpStatus.CREATED);
		String id = id("Twilio");
		assertThat(e.post("/api/balances", body("Other"))).hasStatus(HttpStatus.CREATED);
		startedAgo("Twilio", 6);
		this.job.runAlerts();
		assertThat(alerts(boss) + alerts(emp)).isEqualTo(2);

		// A member deletes it.
		assertThat(e.delete("/api/balances/" + id)).hasStatus(HttpStatus.NO_CONTENT);

		// Hidden from the list and the dashboard summary; the row stays (soft delete); alerts gone.
		assertThat(count("balances")).isEqualTo(2);
		for (Client c : List.of(b, e)) {
			MvcTestResult list = c.get("/api/balances");
			assertThat(list).bodyJson().extractingPath("$.summary.count").isEqualTo(1);
			assertThat(list).bodyJson().extractingPath("$.items.length()").isEqualTo(1);
			assertThat(list).bodyJson().extractingPath("$.items[0].name").isEqualTo("Other");
		}
		assertThat(alerts(boss) + alerts(emp)).isZero();

		// The job skips it.
		this.job.runAlerts();
		assertThat(alerts(boss) + alerts(emp)).isZero();

		// No more changes while in the trash.
		assertThat(e.put("/api/balances/" + id, body("Twilio"))).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(e.post("/api/balances/" + id + "/fix", "{\"amount\":\"5\"}")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(e.delete("/api/balances/" + id)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(b.delete("/api/admin/balances/" + id)).hasStatus(HttpStatus.NOT_FOUND);

		// A member cannot see the trash nor restore; nor the balance (and its history) while in the trash.
		assertThat(e.get("/api/admin/balances/trash")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/admin/balances/" + id + "/restore", "")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.get("/api/balances/" + id)).hasStatus(HttpStatus.NOT_FOUND);

		// The owner sees it in the trash, with who deleted it, and its history.
		MvcTestResult trash = b.get("/api/admin/balances/trash");
		assertThat(trash).hasStatusOk();
		assertThat(trash).bodyJson().extractingPath("$.length()").isEqualTo(1);
		assertThat(trash).bodyJson().extractingPath("$[0].name").isEqualTo("Twilio");
		assertThat(trash).bodyJson().extractingPath("$[0].deletedAt").isNotNull();
		assertThat(trash).bodyJson().extractingPath("$[0].deletedBy.id").isEqualTo(emp.getId().toString());
		MvcTestResult inTrash = b.get("/api/balances/" + id);
		assertThat(inTrash).hasStatusOk();
		assertThat(inTrash).bodyJson().extractingPath("$.balance.deletedBy.id").isEqualTo(emp.getId().toString());
		assertThat(inTrash).bodyJson().extractingPath("$.history[0].type").isEqualTo("DELETED");
		assertThat(inTrash).bodyJson().extractingPath("$.history[0].by.id").isEqualTo(emp.getId().toString());
		assertThat(inTrash).bodyJson().extractingPath("$.history[0].guessBefore").isEqualTo("41.60");
		assertThat(inTrash).bodyJson().extractingPath("$.history[0].amount").isNull();

		// Deleted 3 days ago: the cuts go on while in the trash.
		this.jdbc.update("UPDATE balance_events SET event_date = ? WHERE kind = 'DELETED'", TODAY.minusDays(3));

		// The owner restores: back everywhere, with its settings; the guess goes on from its data.
		MvcTestResult restored = b.post("/api/admin/balances/" + id + "/restore", "");
		assertThat(restored).hasStatusOk();
		assertThat(restored).bodyJson().extractingPath("$.balance.deletedAt").isNull();
		assertThat(restored).bodyJson().extractingPath("$.balance.deletedBy").isNull();
		assertThat(restored).bodyJson().extractingPath("$.balance.lowLimit").isEqualTo("25.00");
		assertThat(restored).bodyJson().extractingPath("$.balance.guess").isEqualTo("41.60");
		assertThat(trashSize(b)).isZero();
		assertThat(e.get("/api/balances")).bodyJson().extractingPath("$.summary.count").isEqualTo(2);

		// History: RESTORED, cuts, DELETED, cuts, CREATED (newest first). Members read it again.
		MvcTestResult detail = e.get("/api/balances/" + id);
		assertThat(detail).hasStatusOk();
		assertThat(detail).bodyJson().extractingPath("$.history[*].type")
			.isEqualTo(List.of("RESTORED", "CUTS", "DELETED", "CUTS", "CREATED"));
		assertThat(detail).bodyJson().extractingPath("$.history[0].by.id").isEqualTo(boss.getId().toString());
		assertThat(detail).bodyJson().extractingPath("$.history[1].count").isEqualTo(3);
		assertThat(detail).bodyJson().extractingPath("$.history[1].total").isEqualTo("19.20");
		assertThat(detail).bodyJson().extractingPath("$.history[3].count").isEqualTo(3);
		assertThat(detail).bodyJson().extractingPath("$.history[3].total").isEqualTo("19.20");

		// Its alerts come again (it runs out in 7 days).
		assertThat(alerts(boss) + alerts(emp)).isEqualTo(2);

		// Restore again: no change. Unknown: 404.
		assertThat(b.post("/api/admin/balances/" + id + "/restore", "")).hasStatusOk();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM balance_events WHERE kind = 'RESTORED'",
				Integer.class)).isEqualTo(1);
		assertThat(b.post("/api/admin/balances/" + java.util.UUID.randomUUID() + "/restore", ""))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void theOwnerDeletesWithTheAdminPathToo() {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");
		assertThat(b.post("/api/admin/balances", body("Twilio"))).hasStatus(HttpStatus.CREATED);
		String id = id("Twilio");
		assertThat(b.delete("/api/admin/balances/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(trashSize(b)).isEqualTo(1);
		assertThat(b.get("/api/balances")).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
	}

	// ---- helpers ----

	private int trashSize(Client owner) {
		MvcTestResult trash = owner.get("/api/admin/balances/trash");
		assertThat(trash).hasStatusOk();
		try {
			return com.jayway.jsonpath.JsonPath.read(trash.getResponse().getContentAsString(), "$.length()");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void startedAgo(String name, int days) {
		this.jdbc.update("UPDATE balances SET start_date = ?, anchor_date = ?, start_amount = 80 WHERE name = ?",
				TODAY.minusDays(days), TODAY.minusDays(days - 1), name);
		this.jdbc.update("UPDATE balance_events SET event_date = ? WHERE balance_id = (SELECT id FROM balances "
				+ "WHERE name = ?)", TODAY.minusDays(days), name);
	}

	private String body(String name) {
		String food = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		return "{\"name\":\"" + name + "\",\"website\":null,\"categoryId\":\"" + food + "\",\"cut\":\"6.40\","
				+ "\"unit\":\"DAY\",\"every\":1,\"startAmount\":\"80\",\"lowLimit\":\"25\",\"warnDays\":7}";
	}

	private String id(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM balances WHERE name = ?", String.class, name);
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private int alerts(User user) {
		return this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind LIKE 'BALANCE_%'",
				Integer.class, user.getId());
	}

}
