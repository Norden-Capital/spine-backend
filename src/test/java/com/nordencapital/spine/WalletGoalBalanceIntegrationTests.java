package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goal money leaves the wallet balance (computed, no fake transactions); taking it out,
 * removing a movement or deleting the goal brings it back. "This month" ignores goal moves.
 */
class WalletGoalBalanceIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Test
	void goalMoneyLeavesAndComesBackToTheBalance() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		String salary = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Salary'",
				String.class);
		assertThat(a.post("/api/wallet/transactions", "{\"type\":\"INCOME\",\"amount\":\"1000\",\"date\":\"" + TODAY
				+ "\",\"description\":\"Pay\",\"categoryId\":\"" + salary + "\",\"fileIds\":[]}"))
			.hasStatus(HttpStatus.CREATED);
		assertDashboard(a, "1000.00", "0.00");

		a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"2000\"}");
		String goal = this.jdbc.queryForObject("SELECT id::text FROM wallet_goals", String.class);
		String base = "/api/wallet/goals/" + goal;

		MvcTestResult added = a.post(base + "/contributions", "{\"amount\":\"300\",\"date\":\"" + TODAY + "\"}");
		assertThat(added).hasStatus(HttpStatus.CREATED);
		assertThat(added).bodyJson().extractingPath("$.saved").isEqualTo("300.00");
		assertThat(added).bodyJson().extractingPath("$.contributions[0].type").isEqualTo("ADD");
		assertDashboard(a, "700.00", "300.00");

		MvcTestResult out = a.post(base + "/take-out", "{\"amount\":\"100\",\"date\":\"" + TODAY + "\",\"note\":\"Shoes\"}");
		assertThat(out).hasStatus(HttpStatus.CREATED);
		assertThat(out).bodyJson().extractingPath("$.saved").isEqualTo("200.00");
		assertThat(out).bodyJson().extractingPath("$.contributions[?(@.type == 'TAKE_OUT')].amount").asArray()
			.containsExactly("100.00");
		assertDashboard(a, "800.00", "200.00");

		// Not more than the goal holds; removing the add would leave it below 0.
		MvcTestResult tooMuch = a.post(base + "/take-out", "{\"amount\":\"200.01\",\"date\":\"" + TODAY + "\"}");
		assertThat(tooMuch).hasStatus(HttpStatus.CONFLICT);
		assertThat(tooMuch).bodyJson().extractingPath("$.error").isEqualTo("not_enough_saved");
		String addId = this.jdbc.queryForObject("SELECT id::text FROM wallet_goal_contributions WHERE amount > 0",
				String.class);
		assertThat(a.delete(base + "/contributions/" + addId)).hasStatus(HttpStatus.CONFLICT);
		assertThat(a.post(base + "/take-out", "{\"amount\":\"0\",\"date\":\"" + TODAY + "\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);

		// Removing the take-out: the money is in the goal again.
		String outId = this.jdbc.queryForObject("SELECT id::text FROM wallet_goal_contributions WHERE amount < 0",
				String.class);
		assertThat(a.delete(base + "/contributions/" + outId)).bodyJson().extractingPath("$.saved").isEqualTo("300.00");
		assertDashboard(a, "700.00", "300.00");

		// Deleting the goal gives the money back.
		assertThat(a.delete(base)).hasStatus(HttpStatus.NO_CONTENT);
		assertDashboard(a, "1000.00", "0.00");
		// No fake transactions were made.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class)).isEqualTo(1);
	}

	@Test
	void goalMoneyIsSavingNeverSpending() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		// An "all spending" budget of $500.
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"500\"}")).hasStatus(HttpStatus.CREATED);
		a.post("/api/wallet/goals", "{\"name\":\"Car\",\"target\":\"5000\"}");
		String goal = this.jdbc.queryForObject("SELECT id::text FROM wallet_goals", String.class);

		assertThat(a.post("/api/wallet/goals/" + goal + "/contributions",
				"{\"amount\":\"1000\",\"date\":\"" + TODAY + "\"}")).hasStatus(HttpStatus.CREATED);

		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.budgets[0].spent").isEqualTo("0.00");
		assertThat(dashboard).bodyJson().extractingPath("$.budgets[0].percent").isEqualTo(0);
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("0.00");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("-1000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo("1000.00");
		assertThat(a.get("/api/wallet/budgets")).bodyJson().extractingPath("$[0].spent").isEqualTo("0.00");
		// No 80% / 100% budget alert.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE kind LIKE 'BUDGET%'",
				Integer.class)).isZero();
	}

	private static void assertDashboard(Client a, String balance, String savedInGoals) {
		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo(balance);
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo(savedInGoals);
		// Goal moves are transfers: this month only counts transactions.
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.in").isEqualTo("1000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("0.00");
	}

}
