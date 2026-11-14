package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.alert.AlertService;
import com.nordencapital.spine.balance.BalanceService;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Balances: the OWNER changes, members read (403 on every write), the guess from the
 * start point, recharge (one expense, proof required), fix, alerts to everyone (once per
 * start point) and the alert scope.
 */
class BalanceIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Casablanca"));

	@Autowired
	private BalanceService job;

	@Autowired
	private AlertService alertService;

	@Test
	void ownerChangesMembersOnlyRead() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");

		assertThat(e.post("/api/admin/balances", body("Twilio", "DAY", 1, null))).hasStatus(HttpStatus.FORBIDDEN);
		MvcTestResult created = b.post("/api/admin/balances", body("Twilio", "DAY", 1, null));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.balance.name").isEqualTo("Twilio");
		assertThat(created).bodyJson().extractingPath("$.balance.website").isEqualTo("twilio.com");
		assertThat(created).bodyJson().extractingPath("$.balance.logoUrls[0]")
			.isEqualTo("https://twilio.com/apple-touch-icon.png");
		assertThat(created).bodyJson().extractingPath("$.balance.guess").isEqualTo("80.00");
		assertThat(created).bodyJson().extractingPath("$.balance.percent").isEqualTo(100);
		assertThat(created).bodyJson().extractingPath("$.balance.perMonth").isEqualTo("194.82");
		assertThat(created).bodyJson().extractingPath("$.balance.nextCut").isEqualTo(TODAY.plusDays(1).toString());
		// 80 / 6.40 = 12.5: empty on the 13th cut.
		assertThat(created).bodyJson().extractingPath("$.balance.emptyDate").isEqualTo(TODAY.plusDays(13).toString());
		assertThat(created).bodyJson().extractingPath("$.balance.daysLeft").isEqualTo(13);
		assertThat(created).bodyJson().extractingPath("$.balance.lowFrom").isEqualTo(TODAY.plusDays(9).toString());
		assertThat(created).bodyJson().extractingPath("$.balance.urgency").isEqualTo("GREEN");
		assertThat(created).bodyJson().extractingPath("$.balance.canEdit").isEqualTo(true);
		assertThat(created).bodyJson().extractingPath("$.history[0].type").isEqualTo("CREATED");
		assertThat(created).bodyJson().extractingPath("$.history[0].amount").isEqualTo("80.00");
		String id = id();

		// Members read, with canEdit false.
		assertThat(e.get("/api/balances")).hasStatusOk();
		assertThat(e.get("/api/balances")).bodyJson().extractingPath("$.items[0].canEdit").isEqualTo(false);
		assertThat(e.get("/api/balances/" + id)).bodyJson().extractingPath("$.balance.name").isEqualTo("Twilio");

		// Every write is 403 for a member.
		String file = FilesIntegrationTests.uploadedId(e.upload("r.pdf", "application/pdf", TestFiles.PDF));
		assertThat(e.put("/api/admin/balances/" + id, body("X", "DAY", 1, null))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/admin/balances/" + id + "/recharge", recharge("10", TODAY, file, null)))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"5\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(e.delete("/api/admin/balances/" + id)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(count("transactions")).isZero();

		// Edit: 8 times a month.
		MvcTestResult edited = b.put("/api/admin/balances/" + id, body("Twilio SMS", "TIMES_A_MONTH", 8, null));
		assertThat(edited).hasStatusOk();
		assertThat(edited).bodyJson().extractingPath("$.balance.name").isEqualTo("Twilio SMS");
		assertThat(edited).bodyJson().extractingPath("$.balance.perMonth").isEqualTo("51.20");
		assertThat(edited).bodyJson().extractingPath("$.balance.startAmount").isEqualTo("80.00");

		// Bad input.
		assertThat(b.post("/api/admin/balances", body("Bad", "TIMES_A_MONTH", 40, null)))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(b.post("/api/admin/balances", body("Bad", "DAY", 1, TODAY.toString())))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(b.post("/api/admin/balances", body("Bad", "DAY", 1, null).replace("\"80\"", "\"-1\"")))
			.hasStatus(HttpStatus.BAD_REQUEST);

		// Delete: gone with its alerts.
		assertThat(b.delete("/api/admin/balances/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(b.get("/api/balances/" + id)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(count("balance_events")).isZero();
	}

	@Test
	void guessFromAPastStartAndUrgentFirst() {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");
		b.post("/api/admin/balances", body("Green", "DAY", 1, null));
		b.post("/api/admin/balances", body("Amber", "DAY", 1, null));
		b.post("/api/admin/balances", body("Red", "DAY", 1, null));
		// Started 6 days ago, first cut 5 days ago: 6 cuts (today included).
		startedAgo("Amber", 6, "80.00");
		startedAgo("Red", 20, "80.00");

		MvcTestResult list = b.get("/api/balances");
		assertThat(list).bodyJson().extractingPath("$.items[*].name").isEqualTo(List.of("Red", "Amber", "Green"));
		assertThat(list).bodyJson().extractingPath("$.items[0].guess").isEqualTo("0.00");
		assertThat(list).bodyJson().extractingPath("$.items[0].empty").isEqualTo(true);
		assertThat(list).bodyJson().extractingPath("$.items[0].urgency").isEqualTo("RED");
		assertThat(list).bodyJson().extractingPath("$.items[1].guess").isEqualTo("41.60");
		assertThat(list).bodyJson().extractingPath("$.items[1].percent").isEqualTo(52);
		assertThat(list).bodyJson().extractingPath("$.items[1].daysLeft").isEqualTo(7);
		assertThat(list).bodyJson().extractingPath("$.items[1].urgency").isEqualTo("AMBER");
		assertThat(list).bodyJson().extractingPath("$.items[2].urgency").isEqualTo("GREEN");
		assertThat(list).bodyJson().extractingPath("$.summary.count").isEqualTo(3);
		assertThat(list).bodyJson().extractingPath("$.summary.needRecharge").isEqualTo(2);
		assertThat(list).bodyJson().extractingPath("$.summary.perMonth").isEqualTo("584.46");

		// The detail groups the guessed cuts after the event.
		MvcTestResult detail = b.get("/api/balances/" + id("Amber"));
		assertThat(detail).bodyJson().extractingPath("$.history[0].type").isEqualTo("CUTS");
		assertThat(detail).bodyJson().extractingPath("$.history[0].count").isEqualTo(6);
		assertThat(detail).bodyJson().extractingPath("$.history[0].from").isEqualTo(TODAY.minusDays(5).toString());
		assertThat(detail).bodyJson().extractingPath("$.history[0].to").isEqualTo(TODAY.toString());
		assertThat(detail).bodyJson().extractingPath("$.history[0].total").isEqualTo("38.40");
		assertThat(detail).bodyJson().extractingPath("$.history[1].type").isEqualTo("CREATED");
	}

	@Test
	void rechargeNeedsAProofAndAddsOneExpense() throws Exception {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");
		b.post("/api/admin/balances", body("Twilio", "DAY", 1, null));
		String id = id();
		startedAgo("Twilio", 6, "80.00");

		// No proof: 400, nothing saved.
		MvcTestResult noProof = b.post("/api/admin/balances/" + id + "/recharge",
				"{\"amount\":\"50\",\"date\":\"" + TODAY + "\",\"fileIds\":[]}");
		assertThat(noProof).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(noProof).bodyJson().extractingPath("$.error").isEqualTo("proof_required");
		assertThat(count("transactions")).isZero();
		assertThat(count("balance_events")).isEqualTo(1);

		String file = FilesIntegrationTests.uploadedId(b.upload("r.pdf", "application/pdf", TestFiles.PDF));
		// Before the start point: 400.
		assertThat(b.post("/api/admin/balances/" + id + "/recharge", recharge("50", TODAY.minusDays(7), file, null)))
			.hasStatus(HttpStatus.BAD_REQUEST);

		MvcTestResult done = b.post("/api/admin/balances/" + id + "/recharge", recharge("50", TODAY, file, "Card 4242"));
		assertThat(done).hasStatusOk();
		// New start = guess 41.60 + 50.
		assertThat(done).bodyJson().extractingPath("$.balance.startAmount").isEqualTo("91.60");
		assertThat(done).bodyJson().extractingPath("$.balance.guess").isEqualTo("91.60");
		assertThat(done).bodyJson().extractingPath("$.history[0].type").isEqualTo("RECHARGE");
		assertThat(done).bodyJson().extractingPath("$.history[0].guessBefore").isEqualTo("41.60");
		assertThat(done).bodyJson().extractingPath("$.history[0].by.name").isNotNull();

		// One company expense: amount, date, category and website of the balance, the proof.
		assertThat(count("transactions")).isEqualTo(1);
		var tx = this.jdbc.queryForMap("SELECT id, type, amount, date, description, website, category_id FROM transactions");
		assertThat(tx.get("type")).isEqualTo("EXPENSE");
		assertThat(tx.get("amount").toString()).isEqualTo("50.00");
		assertThat(tx.get("date").toString()).isEqualTo(TODAY.toString());
		assertThat(tx.get("description")).isEqualTo("Twilio recharge");
		assertThat(tx.get("website")).isEqualTo("twilio.com");
		assertThat(tx.get("category_id").toString()).isEqualTo(food());
		assertThat(done).bodyJson().extractingPath("$.history[0].transactionId").isEqualTo(tx.get("id").toString());
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM files WHERE transaction_id = ?", Integer.class,
				tx.get("id"))).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT text FROM transaction_comments", String.class))
			.isEqualTo("Card 4242");

		// Fix: new start, no expense.
		MvcTestResult fixed = b.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"30\"}");
		assertThat(fixed).hasStatusOk();
		assertThat(fixed).bodyJson().extractingPath("$.balance.guess").isEqualTo("30.00");
		assertThat(fixed).bodyJson().extractingPath("$.history[0].type").isEqualTo("FIX");
		assertThat(fixed).bodyJson().extractingPath("$.history[0].guessBefore").isEqualTo("91.60");
		assertThat(count("transactions")).isEqualTo(1);
		assertThat(b.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"0\"}")).hasStatusOk();
		assertThat(b.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"-3\"}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void alertsGoToEveryoneOncePerStartPoint() {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		User other = activeUser("other@example.com", Role.MEMBER);
		User gone = disabledUser("gone@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");
		b.post("/api/admin/balances", body("Twilio", "DAY", 1, null));
		String id = id();
		assertThat(count("notifications")).isZero();

		// Runs out in 7 days: BALANCE_SOON for everyone active, once.
		startedAgo("Twilio", 6, "80.00");
		this.job.runAlerts();
		this.job.runAlerts();
		for (User user : List.of(boss, emp, other)) {
			assertThat(alerts(user, BalanceService.BALANCE_SOON)).isEqualTo(1);
		}
		assertThat(alerts(gone, BalanceService.BALANCE_SOON)).isZero();
		assertThat(count("notifications")).isEqualTo(3);
		MvcTestResult mine = e.get("/api/alerts");
		assertThat(mine).bodyJson().extractingPath("$.items[0].scope").isEqualTo("COMPANY");
		assertThat(mine).bodyJson().extractingPath("$.items[0].title").isEqualTo("Twilio runs out in 7 days");
		assertThat(mine).bodyJson().extractingPath("$.items[0].link").isEqualTo("/balances/" + id);

		// Fix to 20 (under the $25 limit, empty in 4 cuts): a new start point, so new alerts;
		// the old ones are read.
		b.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"20\"}");
		assertThat(alerts(emp, BalanceService.BALANCE_LOW)).isEqualTo(1);
		assertThat(alerts(emp, BalanceService.BALANCE_SOON)).isEqualTo(2);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE read_at IS NULL", Integer.class))
			.isEqualTo(6);
		this.job.runAlerts();
		assertThat(count("notifications")).isEqualTo(9);

		// Fix to 0: BALANCE_EMPTY only.
		b.post("/api/admin/balances/" + id + "/fix", "{\"amount\":\"0\"}");
		assertThat(alerts(other, BalanceService.BALANCE_EMPTY)).isEqualTo(1);
		assertThat(count("notifications")).isEqualTo(12);
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items[0].title").isEqualTo("Twilio is empty");
	}

	@Test
	void privateAlertsStayPrivate() {
		User boss = activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		Client e = loggedIn("emp@example.com");
		this.jdbc.update("INSERT INTO notifications (id, user_id, scope, kind, title, body, dedupe_key, created_at) "
				+ "VALUES (gen_random_uuid(), ?, 'PRIVATE', 'BUDGET_80', 'Boss budget', 'b', 'k', now())", boss.getId());
		assertThat(e.get("/api/alerts")).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
		Client b = loggedIn("boss@example.com");
		assertThat(b.get("/api/alerts")).bodyJson().extractingPath("$.items[0].scope").isEqualTo("PRIVATE");
		// Only COMPANY or PRIVATE.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.jdbc.update("UPDATE notifications SET scope = 'X'"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	/** Moves the start point (and the first cut) back in time. */
	@Test
	void alertsSkipInactivePeopleAndReachPeopleWhoJoinLater() {
		User boss = activeUser("boss@example.com", Role.OWNER);
		User emp = activeUser("emp@example.com", Role.MEMBER);
		User gone = disabledUser("gone@example.com", Role.MEMBER);
		User newbie = invitedUser("new@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");

		assertThat(b.post("/api/admin/balances", body("Twilio", "DAY", 1, null))).hasStatus(HttpStatus.CREATED);
		assertThat(b.post("/api/admin/balances/" + id() + "/fix", "{\"amount\":\"0\"}")).hasStatusOk();
		assertThat(alerts(boss, BalanceService.BALANCE_EMPTY)).isEqualTo(1);
		assertThat(alerts(emp, BalanceService.BALANCE_EMPTY)).isEqualTo(1);
		// Disabled and invited people get no alert, not even a private one.
		assertThat(alerts(gone, BalanceService.BALANCE_EMPTY)).isZero();
		assertThat(alerts(newbie, BalanceService.BALANCE_EMPTY)).isZero();
		assertThat(this.alertService.add(gone.getId(), AlertService.BUDGET_80, "t", "b", null, "k")).isFalse();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class,
				gone.getId())).isZero();

		// Someone who joins later gets the open company alerts at the next hourly run, once.
		this.jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", newbie.getId());
		this.job.runAlerts();
		this.job.runAlerts();
		assertThat(alerts(newbie, BalanceService.BALANCE_EMPTY)).isEqualTo(1);
		assertThat(alerts(boss, BalanceService.BALANCE_EMPTY)).isEqualTo(1);
	}

	private void startedAgo(String name, int days, String amount) {
		this.jdbc.update("UPDATE balances SET start_date = ?, anchor_date = ?, start_amount = ?::numeric WHERE name = ?",
				TODAY.minusDays(days), TODAY.minusDays(days - 1), amount, name);
		this.jdbc.update("UPDATE balance_events SET event_date = ? WHERE balance_id = (SELECT id FROM balances "
				+ "WHERE name = ?)", TODAY.minusDays(days), name);
	}

	private String body(String name, String unit, int every, String nextCut) {
		return "{\"name\":\"" + name + "\",\"website\":\"https://www.twilio.com/console\",\"categoryId\":\"" + food()
				+ "\",\"cut\":\"6.40\",\"unit\":\"" + unit + "\",\"every\":" + every + ",\"startAmount\":\"80\","
				+ "\"lowLimit\":\"25\",\"warnDays\":7" + ((nextCut != null) ? ",\"nextCut\":\"" + nextCut + "\"" : "")
				+ "}";
	}

	private static String recharge(String amount, LocalDate date, String file, String note) {
		return "{\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"fileIds\":[\"" + file + "\"]"
				+ ((note != null) ? ",\"note\":\"" + note + "\"" : "") + "}";
	}

	private String food() {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
	}

	private String id() {
		return this.jdbc.queryForObject("SELECT id::text FROM balances ORDER BY created_at DESC LIMIT 1", String.class);
	}

	private String id(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM balances WHERE name = ?", String.class, name);
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private int alerts(User user, String kind) {
		return this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?",
				Integer.class, user.getId(), kind);
	}

}
