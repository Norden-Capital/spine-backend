package com.nordencapital.spine;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goal money is wallet transactions: an add is an EXPENSE in "Goals", a take out an INCOME in
 * "From goals" (goal currency). Balance = income - expense. Removing a movement, the goal or the
 * transaction keeps both sides in step. Budgets ignore goal transactions. The V36 backfill keeps
 * every balance the same.
 */
class WalletGoalBalanceIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Test
	void addMoneyIsAnExpenseInGoalsInTheGoalCurrency() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		setWallet(a, "MAD", "10");
		a.post("/api/wallet/transactions", tx("INCOME", "5000", "MAD", "Pay", category("Salary")));
		String goal = id(a.post("/api/wallet/goals", "{\"name\":\"Laptop\",\"target\":\"1000\",\"currency\":\"USD\"}"));

		MvcTestResult added = a.post("/api/wallet/goals/" + goal + "/contributions",
				"{\"amount\":\"100\",\"date\":\"" + TODAY + "\",\"note\":\"Bonus\"}");
		assertThat(added).hasStatus(HttpStatus.CREATED);
		assertThat(added).bodyJson().extractingPath("$.saved").isEqualTo("100.00");
		assertThat(added).bodyJson().extractingPath("$.contributions[0].note").isEqualTo("Bonus");
		String txId = this.jdbc.queryForObject("SELECT transaction_id::text FROM wallet_goal_contributions",
				String.class);
		assertThat(added).bodyJson().extractingPath("$.contributions[0].transactionId").isEqualTo(txId);

		MvcTestResult tx = a.get("/api/wallet/transactions/" + txId);
		assertThat(tx).hasStatus(HttpStatus.OK);
		assertThat(tx).bodyJson().extractingPath("$.type").isEqualTo("EXPENSE");
		assertThat(tx).bodyJson().extractingPath("$.amount").isEqualTo("100.00");
		assertThat(tx).bodyJson().extractingPath("$.currency").isEqualTo("USD");
		assertThat(tx).bodyJson().extractingPath("$.date").isEqualTo(TODAY);
		assertThat(tx).bodyJson().extractingPath("$.description").isEqualTo("Goal: Laptop");
		assertThat(tx).bodyJson().extractingPath("$.goalId").isEqualTo(goal);
		assertThat(tx).bodyJson().extractingPath("$.category.name").isEqualTo("Goals");
		assertThat(tx).bodyJson().extractingPath("$.category.icon").isEqualTo("coins");
		assertThat(tx).bodyJson().extractingPath("$.category.kind").isEqualTo("EXPENSE");
		assertThat(this.jdbc.queryForObject("SELECT system_key FROM wallet_categories WHERE name = 'Goals'",
				String.class)).isEqualTo("GOALS_EXPENSE");
		// A normal transaction has no goal.
		assertThat(a.get("/api/wallet/transactions?size=50")).bodyJson()
			.extractingPath("$.items[?(@.description == 'Pay')].goalId")
			.asArray()
			.containsExactly((Object) null);

		// 100 USD = 1000 MAD: out of the balance once (not twice), counted in this month's out.
		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("4000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo("1000.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("1000.00");
		assertThat(a.get("/api/wallet/transactions?size=50")).bodyJson().extractingPath("$.totals.out")
			.isEqualTo("1000.00");

		// A second add uses the same category, even after the person renamed it.
		String goalsCategory = category("Goals");
		assertThat(a.patch("/api/wallet/categories/" + goalsCategory, "{\"name\":\"Savings\"}"))
			.hasStatus(HttpStatus.OK);
		a.post("/api/wallet/goals/" + goal + "/contributions", "{\"amount\":\"5\",\"date\":\"" + TODAY + "\"}");
		assertThat(this.jdbc.queryForObject("SELECT count(DISTINCT category_id) FROM wallet_transactions "
				+ "WHERE type = 'EXPENSE'", Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_categories WHERE system_key IS NOT NULL",
				Integer.class)).isEqualTo(1);
		// Its kind cannot change (goal adds must stay expenses).
		assertThat(a.patch("/api/wallet/categories/" + goalsCategory, "{\"kind\":\"INCOME\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);

		// A new goal currency (or name) follows on its transactions.
		assertThat(a.put("/api/wallet/goals/" + goal,
				"{\"name\":\"New laptop\",\"target\":\"1000\",\"currency\":\"MAD\"}")).hasStatus(HttpStatus.OK);
		assertThat(this.jdbc.queryForList("SELECT currency || ' ' || description FROM wallet_transactions "
				+ "WHERE type = 'EXPENSE'", String.class)).containsOnly("MAD Goal: New laptop");
	}

	@Test
	void takeOutIsAnIncomeAndUndoRemovesTheTransaction() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		a.post("/api/wallet/transactions", tx("INCOME", "1000", null, "Pay", category("Salary")));
		String goal = id(a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"2000\"}"));
		String base = "/api/wallet/goals/" + goal;

		a.post(base + "/contributions", "{\"amount\":\"300\",\"date\":\"" + TODAY + "\"}");
		assertDashboard(a, "700.00", "300.00", "1000.00", "300.00");

		MvcTestResult out = a.post(base + "/take-out", "{\"amount\":\"100\",\"date\":\"" + TODAY + "\",\"note\":\"Shoes\"}");
		assertThat(out).hasStatus(HttpStatus.CREATED);
		assertThat(out).bodyJson().extractingPath("$.saved").isEqualTo("200.00");
		String outTx = this.jdbc.queryForObject(
				"SELECT transaction_id::text FROM wallet_goal_contributions WHERE amount < 0", String.class);
		MvcTestResult income = a.get("/api/wallet/transactions/" + outTx);
		assertThat(income).bodyJson().extractingPath("$.type").isEqualTo("INCOME");
		assertThat(income).bodyJson().extractingPath("$.amount").isEqualTo("100.00");
		assertThat(income).bodyJson().extractingPath("$.description").isEqualTo("Goal: Trip");
		assertThat(income).bodyJson().extractingPath("$.category.name").isEqualTo("From goals");
		assertThat(income).bodyJson().extractingPath("$.category.kind").isEqualTo("INCOME");
		assertThat(income).bodyJson().extractingPath("$.goalId").isEqualTo(goal);
		assertDashboard(a, "800.00", "200.00", "1100.00", "300.00");

		// Undo of the take out: the movement and its transaction go.
		String outId = this.jdbc.queryForObject("SELECT id::text FROM wallet_goal_contributions WHERE amount < 0",
				String.class);
		assertThat(a.delete(base + "/contributions/" + outId)).bodyJson().extractingPath("$.saved").isEqualTo("300.00");
		assertThat(a.get("/api/wallet/transactions/" + outTx)).hasStatus(HttpStatus.NOT_FOUND);
		assertDashboard(a, "700.00", "300.00", "1000.00", "300.00");

		// Undo of the add too.
		String addId = this.jdbc.queryForObject("SELECT id::text FROM wallet_goal_contributions", String.class);
		assertThat(a.delete(base + "/contributions/" + addId)).hasStatus(HttpStatus.OK);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class)).isEqualTo(1);
		assertDashboard(a, "1000.00", "0.00", "1000.00", "0.00");
	}

	@Test
	void deletingTheGoalRemovesItsTransactions() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		a.post("/api/wallet/transactions", tx("INCOME", "1000", null, "Pay", category("Salary")));
		String trip = id(a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"2000\"}"));
		String car = id(a.post("/api/wallet/goals", "{\"name\":\"Car\",\"target\":\"2000\"}"));
		a.post("/api/wallet/goals/" + trip + "/contributions", "{\"amount\":\"300\",\"date\":\"" + TODAY + "\"}");
		a.post("/api/wallet/goals/" + trip + "/take-out", "{\"amount\":\"50\",\"date\":\"" + TODAY + "\"}");
		a.post("/api/wallet/goals/" + car + "/contributions", "{\"amount\":\"10\",\"date\":\"" + TODAY + "\"}");
		assertDashboard(a, "740.00", "260.00", "1050.00", "310.00");

		assertThat(a.delete("/api/wallet/goals/" + trip)).hasStatus(HttpStatus.NO_CONTENT);
		assertDashboard(a, "990.00", "10.00", "1000.00", "10.00");
		assertThat(this.jdbc.queryForList("SELECT description FROM wallet_transactions ORDER BY description",
				String.class)).containsExactly("Goal: Car", "Pay");
	}

	@Test
	void deletingTheTransactionRemovesTheMovement() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String goal = id(a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"2000\"}"));
		a.post("/api/wallet/goals/" + goal + "/contributions", "{\"amount\":\"300\",\"date\":\"" + TODAY + "\"}");
		a.post("/api/wallet/goals/" + goal + "/take-out", "{\"amount\":\"200\",\"date\":\"" + TODAY + "\"}");
		String addTx = this.jdbc.queryForObject(
				"SELECT transaction_id::text FROM wallet_goal_contributions WHERE amount > 0", String.class);
		String outTx = this.jdbc.queryForObject(
				"SELECT transaction_id::text FROM wallet_goal_contributions WHERE amount < 0", String.class);

		// Same rules as the undo: removing the add would leave the goal below 0.
		MvcTestResult refused = a.delete("/api/wallet/transactions/" + addTx);
		assertThat(refused).hasStatus(HttpStatus.CONFLICT);
		assertThat(refused).bodyJson().extractingPath("$.error").isEqualTo("not_enough_saved");
		assertThat(a.get("/api/wallet/transactions/" + addTx)).hasStatus(HttpStatus.OK);

		assertThat(a.delete("/api/wallet/transactions/" + outTx)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/goals")).bodyJson().extractingPath("$[0].saved").isEqualTo("300.00");
		assertThat(a.delete("/api/wallet/transactions/" + addTx)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/goals")).bodyJson().extractingPath("$[0].saved").isEqualTo("0.00");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_goal_contributions", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class)).isZero();
	}

	@Test
	void aGoalTransactionCannotBeChangedHere() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String goal = id(a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"2000\"}"));
		a.post("/api/wallet/goals/" + goal + "/contributions", "{\"amount\":\"300\",\"date\":\"" + TODAY + "\"}");
		String txId = this.jdbc.queryForObject("SELECT transaction_id::text FROM wallet_goal_contributions",
				String.class);

		MvcTestResult changed = a.put("/api/wallet/transactions/" + txId,
				tx("EXPENSE", "1", null, "Changed", category("Food")));
		assertThat(changed).hasStatus(HttpStatus.CONFLICT);
		assertThat(changed).bodyJson().extractingPath("$.error").isEqualTo("goal_money");
		assertThat(changed).bodyJson().extractingPath("$.message").isEqualTo("Change it on the Goals page.");
		assertThat(this.jdbc.queryForObject("SELECT amount FROM wallet_transactions", BigDecimal.class))
			.isEqualByComparingTo("300");
	}

	@Test
	void goalMoneyIsSavingNeverSpending() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		// An "all spending" budget of $500 and one on the Goals category's neighbour.
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"500\"}")).hasStatus(HttpStatus.CREATED);
		String goal = id(a.post("/api/wallet/goals", "{\"name\":\"Car\",\"target\":\"5000\"}"));

		assertThat(a.post("/api/wallet/goals/" + goal + "/contributions",
				"{\"amount\":\"1000\",\"date\":\"" + TODAY + "\"}")).hasStatus(HttpStatus.CREATED);
		// A budget on the Goals category itself ignores goal money too.
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"100\",\"categoryId\":\"" + category("Goals") + "\"}"))
			.hasStatus(HttpStatus.CREATED);
		a.post("/api/wallet/transactions", tx("EXPENSE", "50", null, "Lunch", category("Food")));

		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.budgets[0].spent").isEqualTo("50.00");
		assertThat(dashboard).bodyJson().extractingPath("$.budgets[0].percent").isEqualTo(10);
		assertThat(dashboard).bodyJson().extractingPath("$.budgets[1].spent").isEqualTo("0.00");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("1050.00");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("-1050.00");
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo("1000.00");
		// No 80% / 100% budget alert.
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM notifications WHERE kind LIKE 'BUDGET%'",
				Integer.class)).isZero();
	}

	@Test
	void backfillKeepsEveryBalanceTheSame() throws Exception {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		User bob = activeUser("bob@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		Client b = wallet("bob@example.com");
		setWallet(a, "MAD", "10");
		a.post("/api/wallet/transactions", tx("INCOME", "5000", "MAD", "Pay", category(ada, "Salary")));
		a.post("/api/wallet/transactions", tx("EXPENSE", "120.50", "USD", "Shoes", category(ada, "Shopping")));
		// Ada already has her own "Goals" category: the goal category becomes "Goals 2".
		assertThat(a.post("/api/wallet/categories", "{\"name\":\"goals\",\"icon\":\"tag\"}"))
			.hasStatus(HttpStatus.CREATED);
		String usdGoal = id(a.post("/api/wallet/goals", "{\"name\":\"Laptop\",\"target\":\"1000\",\"currency\":\"USD\"}"));
		String madGoal = id(a.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"9000\",\"currency\":\"MAD\"}"));
		b.post("/api/wallet/transactions", tx("INCOME", "40", null, "Gift", category(bob, "Salary")));
		String bobGoal = id(b.post("/api/wallet/goals", "{\"name\":\"Bike\",\"target\":\"100\"}"));

		// Movements made before goal money had transactions (no transaction_id).
		oldMovement(usdGoal, "100");
		oldMovement(usdGoal, "50");
		oldMovement(usdGoal, "-30");
		oldMovement(madGoal, "1000");
		oldMovement(madGoal, "-250.50");
		oldMovement(bobGoal, "10");

		// Old rule: balance = income - expense - money in goals.
		BigDecimal adaOld = oldBalance(a);
		BigDecimal bobOld = oldBalance(b);
		assertThat(adaOld).isEqualByComparingTo("1845.50"); // 5000 - 1205 - (120 USD x 10 + 749.50)
		assertThat(bobOld).isEqualByComparingTo("30.00");
		String adaGoals = a.get("/api/wallet/goals").getResponse().getContentAsString();

		runBackfill();

		assertDashboard(a, "1845.50", "1949.50");
		assertDashboard(b, "30.00", "10.00");
		// Goals unchanged (saved, percent, movements), only the transaction links are new.
		assertThat(a.get("/api/wallet/goals").getResponse().getContentAsString()
			.replaceAll("\"transactionId\":\"[^\"]+\"", "\"transactionId\":null")).isEqualTo(adaGoals);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_goal_contributions WHERE transaction_id IS NULL",
				Integer.class)).isZero();
		assertThat(this.jdbc.queryForList("SELECT t.type || ' ' || t.amount || ' ' || t.currency || ' ' || t.description "
				+ "|| ' ' || cat.name FROM wallet_transactions t JOIN wallet_categories cat ON cat.id = t.category_id "
				+ "JOIN wallet_goal_contributions c ON c.transaction_id = t.id WHERE t.user_id = ?", String.class,
				ada.getId()))
			.containsExactlyInAnyOrder("EXPENSE 100.00 USD Goal: Laptop Goals 2", "EXPENSE 50.00 USD Goal: Laptop Goals 2",
					"INCOME 30.00 USD Goal: Laptop From goals", "EXPENSE 1000.00 MAD Goal: Trip Goals 2",
					"INCOME 250.50 MAD Goal: Trip From goals");
		assertThat(this.jdbc.queryForList("SELECT name || ' ' || kind || ' ' || system_key FROM wallet_categories "
				+ "WHERE user_id = ? AND system_key IS NOT NULL", String.class, bob.getId()))
			.containsExactly("Goals EXPENSE GOALS_EXPENSE");

		// Safe to run twice; new movements then use the same categories.
		int transactions = this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class);
		runBackfill();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Integer.class))
			.isEqualTo(transactions);
		a.post("/api/wallet/goals/" + usdGoal + "/take-out", "{\"amount\":\"1\",\"date\":\"" + TODAY + "\"}");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_categories WHERE user_id = ? "
				+ "AND system_key IS NOT NULL", Integer.class, ada.getId())).isEqualTo(2);
		assertDashboard(a, "1855.50", "1939.50");
	}

	// ---- helpers ----

	private void runBackfill() throws Exception {
		this.jdbc.execute(new ClassPathResource("db/migration/V36__backfill_goal_money_transactions.sql")
			.getContentAsString(StandardCharsets.UTF_8));
	}

	private void oldMovement(String goalId, String amount) {
		this.jdbc.update("INSERT INTO wallet_goal_contributions (id, goal_id, amount, date, created_at) "
				+ "VALUES (?, ?::uuid, ?, current_date, now())", UUID.randomUUID(), goalId, new BigDecimal(amount));
	}

	/** Before the backfill the balance did not hold goal money yet: subtract it like the old rule. */
	private static BigDecimal oldBalance(Client client) throws Exception {
		MvcTestResult dashboard = client.get("/api/wallet/dashboard");
		String body = dashboard.getResponse().getContentAsString();
		return new BigDecimal(field(body, "balance")).subtract(new BigDecimal(field(body, "savedInGoals")));
	}

	private static String field(String json, String name) {
		return json.replaceAll("(?s).*?\"" + name + "\":\"([^\"]+)\".*", "$1");
	}

	private static void assertDashboard(Client a, String balance, String savedInGoals) {
		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo(balance);
		assertThat(dashboard).bodyJson().extractingPath("$.savedInGoals").isEqualTo(savedInGoals);
	}

	private static void assertDashboard(Client a, String balance, String savedInGoals, String in, String out) {
		assertDashboard(a, balance, savedInGoals);
		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.in").isEqualTo(in);
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo(out);
	}

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

	private String category(User user, String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE user_id = ? AND name = ?",
				String.class, user.getId(), name);
	}

	private static String tx(String type, String amount, String currency, String description, String categoryId) {
		return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\","
				+ ((currency != null) ? "\"currency\":\"" + currency + "\"," : "") + "\"date\":\"" + TODAY
				+ "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId + "\",\"fileIds\":[]}";
	}

	private static String id(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		try {
			return result.getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
