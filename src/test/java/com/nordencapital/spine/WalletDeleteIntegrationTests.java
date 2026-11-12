package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.wallet.WalletRecurring;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deleted wallet transaction is gone everywhere (list, search count, totals, dashboard,
 * detail) and never comes back, also when a recurring item made it.
 */
class WalletDeleteIntegrationTests extends IntegrationTestBase {

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private WalletRecurring job;

	@Test
	void deletedIncomeIsGoneEverywhere() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String salary = category("Salary");
		MvcTestResult created = a.post("/api/wallet/transactions", "{\"type\":\"INCOME\",\"amount\":\"3000\",\"date\":\""
				+ TODAY + "\",\"description\":\"Pay\",\"categoryId\":\"" + salary + "\",\"fileIds\":[]}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_transactions", String.class);

		assertThat(a.delete("/api/wallet/transactions/" + id)).hasStatus(HttpStatus.NO_CONTENT);

		assertGone(a, id);
	}

	@Test
	void deletedRecurringIncomeIsNotMadeAgain() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String salary = category("Salary");
		// Starts today: today's income is made at once.
		assertThat(a.post("/api/wallet/recurring", "{\"name\":\"Pay\",\"type\":\"INCOME\",\"amount\":\"3000\","
				+ "\"categoryId\":\"" + salary + "\",\"description\":\"Pay\",\"unit\":\"MONTH\",\"every\":1,"
				+ "\"startDate\":\"" + TODAY + "\"}")).hasStatus(HttpStatus.CREATED);
		String recurring = this.jdbc.queryForObject("SELECT id::text FROM wallet_recurring", String.class);
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_transactions", String.class);

		assertThat(a.delete("/api/wallet/transactions/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertGone(a, id);

		// The job, a change of the item, a pause + resume: the deleted date is not made again.
		this.job.runDue();
		assertThat(a.put("/api/wallet/recurring/" + recurring, "{\"name\":\"Pay\",\"type\":\"INCOME\",\"amount\":\"3100\","
				+ "\"categoryId\":\"" + salary + "\",\"description\":\"Pay\",\"unit\":\"MONTH\",\"every\":1,"
				+ "\"startDate\":\"" + TODAY + "\"}")).hasStatus(HttpStatus.OK);
		a.post("/api/wallet/recurring/" + recurring + "/pause", "");
		a.post("/api/wallet/recurring/" + recurring + "/resume", "");
		this.job.runDue();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class)).isZero();
		assertThat(a.get("/api/wallet/dashboard")).bodyJson().extractingPath("$.recent.length()").isEqualTo(0);
		// The item shows that date as skipped; the next one is normal.
		MvcTestResult list = a.get("/api/wallet/recurring");
		assertThat(list).bodyJson().extractingPath("$[0].upcoming[0].status").isEqualTo("SKIPPED");
		assertThat(list).bodyJson().extractingPath("$[0].nextDate").isEqualTo(TODAY.plusMonths(1).toString());
	}

	private void assertGone(Client a, String id) {
		MvcTestResult detail = a.get("/api/wallet/transactions/" + id);
		assertThat(detail).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(detail).bodyJson().extractingPath("$.error").isEqualTo("transaction_not_found");
		MvcTestResult list = a.get("/api/wallet/transactions");
		assertThat(list).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
		assertThat(list).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
		assertThat(list).bodyJson().extractingPath("$.totals.in").isEqualTo("0.00");
		assertThat(a.get("/api/wallet/transactions?size=0&q=pay")).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.recent.length()").isEqualTo(0);
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("0.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.in").isEqualTo("0.00");
		assertThat(a.delete("/api/wallet/transactions/" + id)).hasStatus(HttpStatus.NOT_FOUND);
	}

	private Client wallet(String email) {
		Client client = loggedIn(email);
		assertThat(client.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		return client;
	}

	private String category(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = ?", String.class, name);
	}

}
