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
 * Private wallet currencies (USD, MAD): each item has its own currency (default = my
 * preference), totals are converted into my default currency with my own rate on read.
 */
class WalletCurrencyIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Test
	void defaultCurrencyIsUsedWhenLeftOut() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String food = category("Food");

		// No preferences saved: USD.
		MvcTestResult usd = a.post("/api/wallet/transactions", tx("EXPENSE", "10", null, "Lunch", food));
		assertThat(usd).hasStatus(HttpStatus.CREATED);
		assertThat(usd).bodyJson().extractingPath("$.currency").isEqualTo("USD");

		setWallet(a, "MAD", "10");
		MvcTestResult mad = a.post("/api/wallet/transactions", tx("EXPENSE", "50", null, "Taxi", food));
		assertThat(mad).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		MvcTestResult given = a.post("/api/wallet/transactions", tx("EXPENSE", "7", "USD", "Coffee", food));
		assertThat(given).bodyJson().extractingPath("$.currency").isEqualTo("USD");
		assertThat(a.post("/api/wallet/transactions", tx("EXPENSE", "7", "EUR", "Tea", food)))
			.hasStatus(HttpStatus.BAD_REQUEST);

		// Change without currency keeps it; with currency changes it.
		String madId = id(mad);
		assertThat(a.put("/api/wallet/transactions/" + madId, tx("EXPENSE", "60", null, "Taxi", food))).bodyJson()
			.extractingPath("$.currency")
			.isEqualTo("MAD");
		String usdId = id(usd);
		assertThat(a.put("/api/wallet/transactions/" + usdId, tx("EXPENSE", "10", null, "Lunch", food))).bodyJson()
			.extractingPath("$.currency")
			.isEqualTo("USD");
		assertThat(a.put("/api/wallet/transactions/" + usdId, tx("EXPENSE", "10", "MAD", "Lunch", food))).bodyJson()
			.extractingPath("$.currency")
			.isEqualTo("MAD");
		assertThat(a.get("/api/wallet/transactions?from=2000-01-01")).bodyJson()
			.extractingPath("$.items[*].currency")
			.asArray()
			.containsExactlyInAnyOrder("MAD", "USD", "MAD");

		// Duplicate check compares the currency too: 7 MAD is not a duplicate of 7 USD.
		assertThat(a.post("/api/wallet/transactions", tx("EXPENSE", "7", "MAD", "Coffee", food)))
			.hasStatus(HttpStatus.CREATED);
		assertThat(a.post("/api/wallet/transactions", tx("EXPENSE", "7", "MAD", "Coffee", food)))
			.hasStatus(HttpStatus.CONFLICT);

		// Goals, budgets and recurring items too.
		MvcTestResult goal = a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"1000\"}");
		assertThat(goal).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		assertThat(a.put("/api/wallet/goals/" + id(goal), "{\"name\":\"Trip\",\"target\":\"900\"}")).bodyJson()
			.extractingPath("$.currency")
			.isEqualTo("MAD");
		MvcTestResult budget = a.post("/api/wallet/budgets", "{\"limit\":\"500\"}");
		assertThat(budget).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		assertThat(a.put("/api/wallet/budgets/" + id(budget), "{\"limit\":\"400\",\"currency\":\"USD\"}")).bodyJson()
			.extractingPath("$.currency")
			.isEqualTo("USD");
		MvcTestResult recurring = a.post("/api/wallet/recurring", recurring("Gym", food, null, "2099-01-01"));
		assertThat(recurring).hasStatus(HttpStatus.CREATED);
		assertThat(recurring).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
	}

	@Test
	void dashboardAndListTotalsAreConvertedIntoTheDefaultCurrency() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		setWallet(a, "MAD", "10");
		a.post("/api/wallet/transactions", tx("INCOME", "1000", "MAD", "Pay", category("Salary")));
		a.post("/api/wallet/transactions", tx("EXPENSE", "20", "USD", "Book", category("Shopping")));

		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("800.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.in").isEqualTo("1000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("200.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.net").isEqualTo("800.00");
		assertThat(dashboard).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		assertThat(dashboard).bodyJson().extractingPath("$.usdRate").isEqualTo("10.00");

		MvcTestResult list = a.get("/api/wallet/transactions?from=2000-01-01");
		assertThat(list).bodyJson().extractingPath("$.totals.in").isEqualTo("1000.00");
		assertThat(list).bodyJson().extractingPath("$.totals.out").isEqualTo("200.00");
		assertThat(list).bodyJson().extractingPath("$.totals.net").isEqualTo("800.00");
		assertThat(list).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		assertThat(list).bodyJson().extractingPath("$.usdRate").isEqualTo("10.00");
		// Items keep their own amount and currency.
		assertThat(list).bodyJson().extractingPath("$.items[?(@.currency == 'USD')].amount").asArray()
			.containsExactly("20.00");

		// A new rate changes the totals (never stored).
		setWallet(a, "MAD", "9.5");
		assertThat(a.get("/api/wallet/dashboard")).bodyJson().extractingPath("$.balance").isEqualTo("810.00");

		// Default USD: MAD amounts are divided by the rate, rounded once at the end.
		setWallet(a, "USD", "3");
		MvcTestResult inUsd = a.get("/api/wallet/dashboard");
		assertThat(inUsd).bodyJson().extractingPath("$.balance").isEqualTo("313.33");
		assertThat(inUsd).bodyJson().extractingPath("$.thisMonth.in").isEqualTo("333.33");
		assertThat(inUsd).bodyJson().extractingPath("$.currency").isEqualTo("USD");
		assertThat(inUsd).bodyJson().extractingPath("$.usdRate").isEqualTo("3.00");
	}

	@Test
	void budgetSpentIsConvertedIntoTheBudgetCurrency() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		setWallet(a, "MAD", "10");
		String food = category("Food");
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"100\",\"currency\":\"USD\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"1000\",\"categoryId\":\"" + food + "\"}"))
			.hasStatus(HttpStatus.CREATED);
		a.post("/api/wallet/transactions", tx("EXPENSE", "20", "USD", "Dinner", food));
		a.post("/api/wallet/transactions", tx("EXPENSE", "300", "MAD", "Shoes", category("Shopping")));

		MvcTestResult budgets = a.get("/api/wallet/budgets");
		// Total budget, USD: 20 USD + 300 MAD / 10 = 50 USD.
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category == null)].currency").asArray()
			.containsExactly("USD");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category == null)].spent").asArray()
			.containsExactly("50.00");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category == null)].percent").asArray().containsExactly(50);
		// Food budget, MAD: 20 USD * 10 = 200 MAD.
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].currency").asArray()
			.containsExactly("MAD");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].limit").asArray()
			.containsExactly("1000.00");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].spent").asArray()
			.containsExactly("200.00");

		// 80% of the USD budget: 31 more USD (in MAD).
		a.post("/api/wallet/transactions", tx("EXPENSE", "310", "MAD", "Shirt", category("Shopping")));
		assertThat(this.jdbc.queryForList("SELECT kind FROM notifications", String.class)).containsExactly("BUDGET_80");
	}

	@Test
	void goalsKeepTheirCurrencyAndSavedInGoalsIsConverted() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		setWallet(a, "MAD", "10");
		a.post("/api/wallet/transactions", tx("INCOME", "5000", "MAD", "Pay", category("Salary")));
		MvcTestResult goal = a.post("/api/wallet/goals", "{\"name\":\"Laptop\",\"target\":\"1000\",\"currency\":\"USD\"}");
		assertThat(goal).hasStatus(HttpStatus.CREATED);
		String base = "/api/wallet/goals/" + id(goal);

		MvcTestResult added = a.post(base + "/contributions", "{\"amount\":\"100\",\"date\":\"" + TODAY + "\"}");
		assertThat(added).bodyJson().extractingPath("$.currency").isEqualTo("USD");
		assertThat(added).bodyJson().extractingPath("$.saved").isEqualTo("100.00");
		assertThat(added).bodyJson().extractingPath("$.target").isEqualTo("1000.00");
		assertThat(added).bodyJson().extractingPath("$.percent").isEqualTo(10);

		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo("1000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("4000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.goals[0].currency").isEqualTo("USD");
		assertThat(dashboard).bodyJson().extractingPath("$.goals[0].saved").isEqualTo("100.00");

		MvcTestResult tooMuch = a.post(base + "/take-out", "{\"amount\":\"150\",\"date\":\"" + TODAY + "\"}");
		assertThat(tooMuch).hasStatus(HttpStatus.CONFLICT);
		assertThat(tooMuch).bodyJson().extractingPath("$.message").isEqualTo("This goal holds only $100.00");
	}

	@Test
	void recurringMakesTransactionsInItsCurrency() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		MvcTestResult rent = a.post("/api/wallet/recurring", recurring("Rent", category("Bills"), "MAD", TODAY));
		assertThat(rent).hasStatus(HttpStatus.CREATED);
		assertThat(rent).bodyJson().extractingPath("$.currency").isEqualTo("MAD");
		assertThat(this.jdbc.queryForObject("SELECT currency FROM wallet_transactions WHERE description = 'Rent'",
				String.class)).isEqualTo("MAD");
		// Default USD, rate 10: 500 MAD = 50 USD.
		assertThat(a.get("/api/wallet/dashboard")).bodyJson().extractingPath("$.balance").isEqualTo("-50.00");
	}

	// ---- helpers ----

	private Client wallet(String email) {
		Client client = loggedIn(email);
		assertThat(client.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		return client;
	}

	private void setWallet(Client client, String currency, String rate) {
		assertThat(client.put("/api/me/preferences", "{\"dateFormat\":\"DD/MM/YYYY\",\"timeZone\":null,"
				+ "\"theme\":\"SYSTEM\",\"font\":\"GEIST\",\"textSize\":\"NORMAL\",\"walletCurrency\":\"" + currency
				+ "\",\"walletUsdRate\":\"" + rate + "\"}")).hasStatus(HttpStatus.OK);
	}

	private String category(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = ?", String.class, name);
	}

	private static String tx(String type, String amount, String currency, String description, String categoryId) {
		return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\","
				+ ((currency != null) ? "\"currency\":\"" + currency + "\"," : "") + "\"date\":\"" + TODAY
				+ "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId + "\",\"fileIds\":[]}";
	}

	private static String recurring(String name, String categoryId, String currency, String start) {
		return "{\"name\":\"" + name + "\",\"type\":\"EXPENSE\",\"amount\":\"500\","
				+ ((currency != null) ? "\"currency\":\"" + currency + "\"," : "") + "\"categoryId\":\"" + categoryId
				+ "\",\"description\":\"" + name + "\"," + repeatJson("MONTHLY") + ",\"startDate\":\"" + start + "\"}";
	}

	private static String id(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
