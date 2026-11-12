package com.nordencapital.spine;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.recurring.CompanyRecurring;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.wallet.WalletRecurring;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeat items (wallet and company): skip one date, undo a skip, pause until a date, and
 * the list of the next dates with their status.
 */
class RecurringSkipIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private WalletRecurring walletJob;

	@Autowired
	private CompanyRecurring companyJob;

	@Test
	void walletSkipUndoAndPauseUntil() {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategory("ada@example.com", "Bills");
		MvcTestResult created = a.post("/api/wallet/recurring", walletBody("Gym", bills, TODAY.plusDays(7)));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.upcoming.length()").isEqualTo(6);
		assertThat(created).bodyJson().extractingPath("$.upcoming[0].status").isEqualTo("UPCOMING");
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_recurring", String.class);
		String base = "/api/wallet/recurring/" + id;

		// Skip the first one: the next date moves on, the list shows it skipped.
		MvcTestResult skipped = a.post(base + "/skips", date(TODAY.plusDays(7)));
		assertThat(skipped).hasStatus(HttpStatus.OK);
		assertThat(skipped).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(14).toString());
		assertThat(skipped).bodyJson().extractingPath("$.upcoming[0].date").isEqualTo(TODAY.plusDays(7).toString());
		assertThat(skipped).bodyJson().extractingPath("$.upcoming[0].status").isEqualTo("SKIPPED");
		assertThat(skipped).bodyJson().extractingPath("$.upcoming[1].status").isEqualTo("UPCOMING");

		// Bad dates; other users cannot touch it.
		assertThat(a.post(base + "/skips", date(TODAY.plusDays(8)))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(a.post(base + "/skips", "{\"date\":\"tomorrow\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(wallet("bob@example.com").post(base + "/skips", date(TODAY.plusDays(14))))
			.hasStatus(HttpStatus.NOT_FOUND);

		// Undo a skip before its date.
		a.post(base + "/skips", date(TODAY.plusDays(14)));
		MvcTestResult undone = a.delete(base + "/skips/" + TODAY.plusDays(14));
		assertThat(undone).hasStatus(HttpStatus.OK);
		assertThat(undone).bodyJson().extractingPath("$.upcoming[1].status").isEqualTo("UPCOMING");

		// When the skipped day comes, nothing is made; the next one stays normal.
		this.jdbc.update("UPDATE wallet_recurring SET start_date = ?, next_date = ?", Date.valueOf(TODAY),
				Date.valueOf(TODAY));
		this.jdbc.update("UPDATE wallet_recurring_skips SET skip_date = ?", Date.valueOf(TODAY));
		assertThat(this.walletJob.runDue()).isZero();
		assertThat(count("wallet_transactions")).isZero();
		assertThat(a.get("/api/wallet/recurring")).bodyJson().extractingPath("$[0].nextDate")
			.isEqualTo(TODAY.plusDays(7).toString());
		// Too late to undo on the day itself.
		MvcTestResult late = a.delete(base + "/skips/" + TODAY);
		assertThat(late).hasStatus(HttpStatus.CONFLICT);
		assertThat(late).bodyJson().extractingPath("$.error").isEqualTo("too_late");

		// Pause until a date: nothing before it, then normal by itself.
		assertThat(a.post(base + "/pause-until", date(TODAY))).hasStatus(HttpStatus.BAD_REQUEST);
		MvcTestResult paused = a.post(base + "/pause-until", date(TODAY.plusDays(15)));
		assertThat(paused).bodyJson().extractingPath("$.pausedUntil").isEqualTo(TODAY.plusDays(15).toString());
		assertThat(paused).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(21).toString());
		assertThat(paused).bodyJson().extractingPath("$.active").isEqualTo(true);
		assertThat(paused).bodyJson().extractingPath("$.upcoming[1].status").isEqualTo("PAUSED");
		assertThat(paused).bodyJson().extractingPath("$.upcoming[3].status").isEqualTo("UPCOMING");
		// Resume ends it early.
		MvcTestResult resumed = a.post(base + "/resume", "");
		assertThat(resumed).bodyJson().extractingPath("$.pausedUntil").isNull();
		assertThat(resumed).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(7).toString());
		// A plain pause shows every date paused.
		assertThat(a.post(base + "/pause", "")).bodyJson().extractingPath("$.upcoming[1].status").isEqualTo("PAUSED");
	}

	@Test
	void walletCannotSkipWhatWasAlreadyMade() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategory("ada@example.com", "Bills");
		assertThat(a.post("/api/wallet/recurring", walletBody("Rent", bills, TODAY))).hasStatus(HttpStatus.CREATED);
		assertThat(count("wallet_transactions")).isEqualTo(1);
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_recurring", String.class);
		MvcTestResult made = a.post("/api/wallet/recurring/" + id + "/skips", date(TODAY));
		assertThat(made).hasStatus(HttpStatus.CONFLICT);
		assertThat(made).bodyJson().extractingPath("$.error").isEqualTo("already_created");
		assertThat(a.get("/api/wallet/recurring")).bodyJson().extractingPath("$[0].upcoming[0].status")
			.isEqualTo("DONE");
	}

	@Test
	void companySkipUndoAndPauseUntil() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		Client b = loggedIn("boss@example.com");
		Client e = loggedIn("emp@example.com");
		assertThat(b.post("/api/admin/recurring", companyBody("Rent", TODAY.plusDays(7)))).hasStatus(HttpStatus.CREATED);
		String id = this.jdbc.queryForObject("SELECT id::text FROM company_recurring", String.class);
		String base = "/api/admin/recurring/" + id;

		assertThat(e.post(base + "/skips", date(TODAY.plusDays(7)))).hasStatus(HttpStatus.FORBIDDEN);
		MvcTestResult skipped = b.post(base + "/skips", date(TODAY.plusDays(7)));
		assertThat(skipped).hasStatus(HttpStatus.OK);
		assertThat(skipped).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(14).toString());
		assertThat(skipped).bodyJson().extractingPath("$.upcoming[0].status").isEqualTo("SKIPPED");
		// Employees see the same list (read only).
		assertThat(e.get("/api/recurring/" + id)).bodyJson().extractingPath("$.upcoming[0].status")
			.isEqualTo("SKIPPED");

		// The skipped day comes: no reminder; the next one stays normal.
		this.jdbc.update("UPDATE company_recurring SET start_date = ?, next_date = ?", Date.valueOf(TODAY),
				Date.valueOf(TODAY));
		this.jdbc.update("UPDATE company_recurring_skips SET skip_date = ?", Date.valueOf(TODAY));
		this.companyJob.runDue();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM company_recurring_occurrences WHERE due_date = ?",
				Integer.class, Date.valueOf(TODAY))).isZero();
		assertThat(b.get("/api/recurring/due")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(b.delete(base + "/skips/" + TODAY)).hasStatus(HttpStatus.CONFLICT);

		// Undo before the date.
		b.post(base + "/skips", date(TODAY.plusDays(14)));
		MvcTestResult undone = b.delete(base + "/skips/" + TODAY.plusDays(14));
		assertThat(undone).bodyJson().extractingPath("$.upcoming[2].date").isEqualTo(TODAY.plusDays(14).toString());
		assertThat(undone).bodyJson().extractingPath("$.upcoming[2].status").isEqualTo("UPCOMING");

		// Pause until a date, then resume.
		MvcTestResult paused = b.post(base + "/pause-until", date(TODAY.plusDays(15)));
		assertThat(paused).bodyJson().extractingPath("$.pausedUntil").isEqualTo(TODAY.plusDays(15).toString());
		assertThat(paused).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(21).toString());
		assertThat(paused).bodyJson().extractingPath("$.upcoming[0].status").isEqualTo("SKIPPED");
		assertThat(paused).bodyJson().extractingPath("$.upcoming[1].status").isEqualTo("PAUSED");
		assertThat(e.post(base + "/pause-until", date(TODAY.plusDays(15)))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(b.post(base + "/resume", "")).bodyJson().extractingPath("$.pausedUntil").isNull();

		// A reminder that already exists is skipped with "skip this time", not here.
		assertThat(b.post("/api/admin/recurring", companyBody("Google", TODAY))).hasStatus(HttpStatus.CREATED);
		String google = this.jdbc.queryForObject("SELECT id::text FROM company_recurring WHERE description = 'Google'",
				String.class);
		MvcTestResult exists = b.post("/api/admin/recurring/" + google + "/skips", date(TODAY));
		assertThat(exists).hasStatus(HttpStatus.CONFLICT);
		assertThat(exists).bodyJson().extractingPath("$.error").isEqualTo("occurrence_exists");
		assertThat(b.get("/api/recurring/" + google)).bodyJson().extractingPath("$.upcoming[0].status")
			.isEqualTo("DUE");
	}

	private Client wallet(String email) {
		Client client = loggedIn(email);
		client.post("/api/wallet/pin", "{\"pin\":\"123456\"}");
		return client;
	}

	private String walletCategory(String email, String name) {
		return this.jdbc.queryForObject("SELECT c.id::text FROM wallet_categories c JOIN users u ON u.id = c.user_id "
				+ "WHERE u.email = ? AND c.name = ?", String.class, email, name);
	}

	private static String walletBody(String name, String categoryId, LocalDate start) {
		return "{\"name\":\"" + name + "\",\"type\":\"EXPENSE\",\"amount\":\"50\",\"categoryId\":\"" + categoryId
				+ "\",\"description\":\"" + name + "\"," + repeatJson("WEEKLY") + ",\"startDate\":\"" + start + "\"}";
	}

	private String companyBody(String description, LocalDate start) {
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		return "{\"type\":\"EXPENSE\",\"amount\":\"1200\",\"description\":\"" + description + "\",\"categoryId\":\""
				+ category + "\"," + repeatJson("WEEKLY") + ",\"startDate\":\"" + start + "\"}";
	}

	private static String date(LocalDate date) {
		return "{\"date\":\"" + date + "\"}";
	}

	private int count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
