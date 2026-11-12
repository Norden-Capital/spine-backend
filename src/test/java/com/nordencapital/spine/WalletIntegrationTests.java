package com.nordencapital.spine;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.wallet.WalletRecurring;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The private wallet: PIN and lock, privacy (even against the OWNER), categories,
 * transactions, files, budgets and alerts, goals, recurring job, dashboard.
 */
class WalletIntegrationTests extends IntegrationTestBase {

	private static final String PIN = "123456";

	private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

	@Autowired
	private WalletRecurring recurringJob;

	// ---- PIN and lock ----

	@Test
	void pinSetUnlockAndLock() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");

		assertThat(a.get("/api/wallet/status")).bodyJson().isStrictlyEqualTo(
				"{\"hasPin\":false,\"unlocked\":false,\"lockedUntil\":null}");
		assertError(a.get("/api/wallet/dashboard"), HttpStatus.CONFLICT, "no_pin");
		assertError(a.post("/api/wallet/unlock", "{\"pin\":\"123456\"}"), HttpStatus.CONFLICT, "no_pin");
		assertError(a.post("/api/wallet/pin", "{\"pin\":\"12345\"}"), HttpStatus.BAD_REQUEST, "validation_error");
		assertError(a.post("/api/wallet/pin", "{\"pin\":\"12345a\"}"), HttpStatus.BAD_REQUEST, "validation_error");

		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.unlocked").isEqualTo(true);
		assertThat(a.get("/api/wallet/categories")).hasStatus(HttpStatus.OK);
		assertError(a.post("/api/wallet/pin", "{\"pin\":\"654321\"}"), HttpStatus.CONFLICT, "pin_exists");
		assertThat(this.jdbc.queryForObject("SELECT pin_hash FROM wallet_settings", String.class)).startsWith("{argon2");

		a.post("/api/wallet/lock", "");
		assertError(a.get("/api/wallet/categories"), HttpStatus.LOCKED, "wallet_locked");
		assertError(a.post("/api/wallet/unlock", "{\"pin\":\"000000\"}"), HttpStatus.BAD_REQUEST, "invalid_pin");
		assertThat(a.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/categories")).hasStatus(HttpStatus.OK);

		// A new login (new session) is locked again.
		Client other = loggedIn("ada@example.com");
		assertError(other.get("/api/wallet/dashboard"), HttpStatus.LOCKED, "wallet_locked");
		// Logout locks it.
		a.post("/api/auth/logout", "");
		Client again = loggedIn("ada@example.com");
		assertThat(again.get("/api/wallet/status")).bodyJson().extractingPath("$.unlocked").isEqualTo(false);
	}

	@Test
	void fiveWrongPinsLockForFiveMinutes() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		a.post("/api/wallet/lock", "");
		for (int i = 0; i < 4; i++) {
			assertError(a.post("/api/wallet/unlock", "{\"pin\":\"000000\"}"), HttpStatus.BAD_REQUEST, "invalid_pin");
		}
		assertError(a.post("/api/wallet/unlock", "{\"pin\":\"000000\"}"), HttpStatus.TOO_MANY_REQUESTS,
				"too_many_attempts");
		assertError(a.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}"), HttpStatus.TOO_MANY_REQUESTS,
				"too_many_attempts");
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.lockedUntil").isNotNull();

		// After the lock time: the right PIN works.
		this.jdbc.update("UPDATE wallet_settings SET locked_until = now() - interval '1 second'");
		assertThat(a.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/status")).bodyJson().extractingPath("$.lockedUntil").isNull();
	}

	@Test
	void resetPinWithAccountPassword() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		a.post("/api/wallet/lock", "");
		assertError(a.post("/api/wallet/pin/reset", "{\"password\":\"wrong-password-1\",\"newPin\":\"654321\"}"),
				HttpStatus.BAD_REQUEST, "invalid_password");
		assertThat(a.post("/api/wallet/pin/reset", "{\"password\":\"" + PASSWORD + "\",\"newPin\":\"654321\"}"))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/wallet/dashboard")).hasStatus(HttpStatus.OK);
		a.post("/api/wallet/lock", "");
		assertError(a.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}"), HttpStatus.BAD_REQUEST, "invalid_pin");
		assertThat(a.post("/api/wallet/unlock", "{\"pin\":\"654321\"}")).hasStatus(HttpStatus.NO_CONTENT);
	}

	// ---- privacy ----

	@Test
	void walletIsPrivateEvenForTheOwner() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("owner@example.com", Role.OWNER);
		activeUser("admin@example.com", Role.MEMBER);
		Client ada = wallet("ada@example.com");
		String category = walletCategoryId(ada, "Food");
		String file = FilesIntegrationTests.uploadedId(ada.upload("private.pdf", "application/pdf", TestFiles.PDF));
		String tx = created(ada.post("/api/wallet/transactions",
				walletBody("EXPENSE", "15.00", TODAY.toString(), "Doctor", category, List.of(file))));
		ada.post("/api/wallet/goals", "{\"name\":\"Trip\",\"target\":\"1000\"}");
		ada.post("/api/wallet/budgets", "{\"limit\":\"100\"}");

		for (String email : List.of("owner@example.com", "admin@example.com")) {
			Client boss = wallet(email);
			assertError(boss.get("/api/wallet/transactions/" + tx), HttpStatus.NOT_FOUND, "transaction_not_found");
			assertError(boss.put("/api/wallet/transactions/" + tx,
					walletBody("EXPENSE", "1.00", TODAY.toString(), "x", category, List.of())),
					HttpStatus.NOT_FOUND, "transaction_not_found");
			assertError(boss.delete("/api/wallet/transactions/" + tx), HttpStatus.NOT_FOUND, "transaction_not_found");
			assertThat(boss.get("/api/wallet/transactions?from=2000-01-01")).bodyJson()
				.extractingPath("$.totalItems")
				.isEqualTo(0);
			assertThat(boss.get("/api/wallet/goals")).bodyJson().extractingPath("$.length()").isEqualTo(0);
			assertThat(boss.get("/api/wallet/budgets")).bodyJson().extractingPath("$.length()").isEqualTo(0);
			assertThat(boss.get("/api/wallet/categories")).bodyJson()
				.extractingPath("$[*].id")
				.asArray()
				.doesNotContain(category);
			assertError(boss.patch("/api/wallet/categories/" + category, "{\"name\":\"Mine\"}"), HttpStatus.NOT_FOUND,
					"category_not_found");
			assertError(boss.get("/api/files/" + file), HttpStatus.NOT_FOUND, "file_not_found");
			// Admin lists show nothing of the wallet.
			assertThat(boss.get("/api/admin/users").getResponse().getContentAsString()).doesNotContain("Doctor");
		}
		// Even the owner of the wallet needs it unlocked to see the file.
		ada.post("/api/wallet/lock", "");
		assertError(ada.get("/api/files/" + file), HttpStatus.LOCKED, "wallet_locked");
		ada.post("/api/wallet/unlock", "{\"pin\":\"" + PIN + "\"}");
		assertThat(ada.get("/api/files/" + file)).hasStatus(HttpStatus.OK);
	}

	// ---- categories ----

	@Test
	void startingCategoriesAndSubcategories() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		assertThat(a.get("/api/wallet/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Bills", "Client payment", "Food", "Fun", "Health", "Investment", "Other", "Other income",
					"Refund", "Salary", "Shopping", "Transport");
		assertThat(a.get("/api/wallet/categories?kind=INCOME")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Client payment", "Investment", "Other income", "Refund", "Salary");

		String food = walletCategoryId(a, "Food");
		MvcTestResult child = a.post("/api/wallet/categories",
				"{\"name\":\"Restaurants\",\"icon\":\"utensils\",\"parentId\":\"" + food + "\"}");
		assertThat(child).hasStatus(HttpStatus.CREATED);
		assertThat(child).bodyJson().extractingPath("$.parentId").isEqualTo(food);
		assertError(a.post("/api/wallet/categories", "{\"name\":\"food\",\"icon\":\"utensils\"}"), HttpStatus.CONFLICT,
				"name_taken");
		String restaurants = walletCategoryId(a, "Restaurants");
		assertError(a.post("/api/wallet/categories",
				"{\"name\":\"Deep\",\"icon\":\"utensils\",\"parentId\":\"" + restaurants + "\"}"), HttpStatus.BAD_REQUEST,
				"validation_error");

		a.post("/api/wallet/categories/" + food + "/archive", "");
		assertThat(a.get("/api/wallet/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.doesNotContain("Food", "Restaurants");
		assertThat(a.get("/api/wallet/categories/all")).bodyJson().extractingPath("$.length()").isEqualTo(13);

	}

	// ---- transactions ----

	@Test
	void transactionsCrudFilesOptionalAndHardDelete() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String food = walletCategoryId(a, "Food");
		String salary = walletCategoryId(a, "Salary");

		MvcTestResult noFile = a.post("/api/wallet/transactions",
				walletBody("EXPENSE", "12.5", TODAY.toString(), "Groceries", food, List.of()));
		assertThat(noFile).hasStatus(HttpStatus.CREATED);
		assertThat(noFile).bodyJson().extractingPath("$.amount").isEqualTo("12.50");
		assertThat(noFile).bodyJson().extractingPath("$.category.name").isEqualTo("Food");
		assertThat(noFile).bodyJson().extractingPath("$.files.length()").isEqualTo(0);
		assertThat(noFile).bodyJson().extractingPath("$.recurringId").isNull();

		String file = FilesIntegrationTests.uploadedId(a.upload("slip.jpg", "image/jpeg", TestFiles.JPEG));
		String salaryTx = created(a.post("/api/wallet/transactions",
				walletBody("INCOME", "3000", TODAY.toString(), "Salary", salary, List.of(file))));
		String key = this.jdbc.queryForObject("SELECT storage_key FROM files", String.class);

		// Duplicate warning (my own only).
		MvcTestResult duplicate = a.post("/api/wallet/transactions",
				walletBody("EXPENSE", "12.50", TODAY.toString(), "Again", food, List.of()));
		assertError(duplicate, HttpStatus.CONFLICT, "possible_duplicate");
		assertThat(duplicate).bodyJson().extractingPath("$.matches[0].description").isEqualTo("Groceries");
		assertThat(a.post("/api/wallet/transactions", TransactionsIntegrationTests.confirmed(
				walletBody("EXPENSE", "12.50", TODAY.toString(), "Again", food, List.of()))))
			.hasStatus(HttpStatus.CREATED);

		assertThat(a.put("/api/wallet/transactions/" + salaryTx,
				walletBody("INCOME", "3100", TODAY.toString(), "Salary + bonus", salary, List.of(file))))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.amount")
			.isEqualTo("3100.00");
		assertError(a.post("/api/wallet/transactions",
				walletBody("EXPENSE", "1", TODAY.plusDays(2).toString(), "Future", food, List.of())),
				HttpStatus.BAD_REQUEST, "future_date");

		assertThat(a.delete("/api/wallet/transactions/" + salaryTx)).hasStatus(HttpStatus.NO_CONTENT);
		assertError(a.get("/api/wallet/transactions/" + salaryTx), HttpStatus.NOT_FOUND, "transaction_not_found");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
		assertThat(Path.of("target/test-files", key)).doesNotExist();

		MvcTestResult list = a.get("/api/wallet/transactions?q=groc");
		assertThat(list).bodyJson().extractingPath("$.items[*].description").asArray().containsExactly("Groceries");
		assertThat(a.get("/api/wallet/transactions")).bodyJson().extractingPath("$.totals.out").isEqualTo("25.00");
	}

	@Test
	void walletCannotUseCompanyOrOtherPeoplesThings() {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		Client b = wallet("bob@example.com");
		String bobsFood = walletCategoryId(b, "Food");
		String companyFood = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'",
				String.class);
		String bobsFile = FilesIntegrationTests.uploadedId(b.upload("b.pdf", "application/pdf", TestFiles.PDF));

		assertError(a.post("/api/wallet/transactions",
				walletBody("EXPENSE", "1", TODAY.toString(), "x", bobsFood, List.of())), HttpStatus.BAD_REQUEST,
				"validation_error");
		assertError(a.post("/api/wallet/transactions",
				walletBody("EXPENSE", "1", TODAY.toString(), "x", companyFood, List.of())), HttpStatus.BAD_REQUEST,
				"validation_error");
		assertError(a.post("/api/wallet/transactions", walletBody("EXPENSE", "1", TODAY.toString(), "x",
				walletCategoryId(a, "Food"), List.of(bobsFile))), HttpStatus.BAD_REQUEST, "validation_error");
	}

	// ---- budgets and alerts ----

	@Test
	void budgetsCountChildrenAndAlertOncePerMonth() throws Exception {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String food = walletCategoryId(a, "Food");
		a.post("/api/wallet/categories", "{\"name\":\"Restaurants\",\"icon\":\"utensils\",\"parentId\":\"" + food + "\"}");
		String restaurants = walletCategoryId(a, "Restaurants");

		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"100\",\"categoryId\":\"" + food + "\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertError(a.post("/api/wallet/budgets", "{\"limit\":\"50\",\"categoryId\":\"" + food + "\"}"),
				HttpStatus.CONFLICT, "budget_exists");
		assertThat(a.post("/api/wallet/budgets", "{\"limit\":\"1000\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertError(a.post("/api/wallet/budgets", "{\"limit\":\"5\"}"), HttpStatus.CONFLICT,
				"budget_exists");

		addWallet(a, "EXPENSE", "50.00", restaurants, "Dinner");
		addWallet(a, "EXPENSE", "35.00", food, "Market");
		MvcTestResult budgets = a.get("/api/wallet/budgets");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].spent").asArray().containsExactly("85.00");
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].percent").asArray().containsExactly(85);
		assertThat(budgets).bodyJson().extractingPath("$[?(@.category != null)].status").asArray().containsExactly("WARNING");
		assertThat(alertKinds(a)).containsExactly("BUDGET_80");

		addWallet(a, "EXPENSE", "20.00", food, "Snacks");
		addWallet(a, "EXPENSE", "1.00", food, "Gum");
		assertThat(a.get("/api/wallet/budgets")).bodyJson()
			.extractingPath("$[?(@.category != null)].status")
			.asArray()
			.containsExactly("EXCEEDED");
		assertThat(alertKinds(a)).containsExactlyInAnyOrder("BUDGET_80", "BUDGET_100");
		assertThat(a.get("/api/alerts").getResponse().getContentAsString()).doesNotContain("85.00").doesNotContain("100.00");
	}

	// ---- goals ----

	@Test
	void goalsAndContributionsDoNotChangeBalance() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		MvcTestResult goal = a.post("/api/wallet/goals",
				"{\"name\":\" New laptop \",\"target\":\"1200\",\"deadline\":\"2027-06-30\"}");
		assertThat(goal).hasStatus(HttpStatus.CREATED);
		assertThat(goal).bodyJson().extractingPath("$.name").isEqualTo("New laptop");
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_goals", String.class);

		a.post("/api/wallet/goals/" + id + "/contributions", "{\"amount\":\"300\",\"date\":\"2026-11-01\"}");
		MvcTestResult updated = a.post("/api/wallet/goals/" + id + "/contributions",
				"{\"amount\":\"150.50\",\"date\":\"2026-11-10\",\"note\":\"Bonus\"}");
		assertThat(updated).hasStatus(HttpStatus.CREATED);
		assertThat(updated).bodyJson().extractingPath("$.saved").isEqualTo("450.50");
		assertThat(updated).bodyJson().extractingPath("$.percent").isEqualTo(37);
		assertThat(updated).bodyJson().extractingPath("$.contributions[0].note").isEqualTo("Bonus");

		String contribution = this.jdbc.queryForObject(
				"SELECT id::text FROM wallet_goal_contributions WHERE note = 'Bonus'", String.class);
		assertThat(a.delete("/api/wallet/goals/" + id + "/contributions/" + contribution)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.saved")
			.isEqualTo("300.00");
		assertThat(a.get("/api/wallet/dashboard")).bodyJson().extractingPath("$.balance").isEqualTo("0.00");
		assertError(a.post("/api/wallet/goals/" + id + "/contributions", "{\"amount\":\"0\",\"date\":\"2026-11-01\"}"),
				HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(a.delete("/api/wallet/goals/" + id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM wallet_goal_contributions", Integer.class)).isZero();
	}

	// ---- recurring ----

	@Test
	void recurringDueTodayIsCreatedOnceWithAlert() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategoryId(a, "Bills");
		MvcTestResult created = a.post("/api/wallet/recurring", recurringBody("Rent", bills, "MONTHLY", TODAY));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusMonths(1).toString());
		assertThat(walletCount("Rent")).isEqualTo(1);

		this.recurringJob.runDue();
		this.recurringJob.runDue();
		assertThat(walletCount("Rent")).isEqualTo(1);
		assertThat(alertKinds(a)).contains("RECURRING_CREATED");
		assertThat(a.get("/api/wallet/transactions")).bodyJson().extractingPath("$.items[0].recurringId").isNotNull();
	}

	@Test
	void biweeklyRecurringIsAllowed() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategoryId(a, "Bills");
		MvcTestResult created = a.post("/api/wallet/recurring",
				recurringBody("Cleaner", bills, "BIWEEKLY", TODAY.plusDays(3)));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.unit").isEqualTo("WEEK");
		assertThat(created).bodyJson().extractingPath("$.every").isEqualTo(2);
		assertThat(created).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(3).toString());
	}

	@Test
	void recurringJobCatchesUpAndWarnsTheDayBefore() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategoryId(a, "Bills");
		a.post("/api/wallet/recurring", recurringBody("Gym", bills, "WEEKLY", TODAY.plusDays(1)));
		assertThat(walletCount("Gym")).isZero();

		// Tomorrow is due: a warning, once.
		this.recurringJob.runDue();
		this.recurringJob.runDue();
		assertThat(alertKinds(a)).containsExactly("RECURRING_DUE_TOMORROW");

		// Missed for 3 weeks (server down): all 3 are made, once each.
		this.jdbc.update("UPDATE wallet_recurring SET start_date = ?, next_date = ?",
				java.sql.Date.valueOf(TODAY.minusWeeks(2)), java.sql.Date.valueOf(TODAY.minusWeeks(2)));
		assertThat(this.recurringJob.runDue()).isEqualTo(3);
		assertThat(this.recurringJob.runDue()).isZero();
		assertThat(walletCount("Gym")).isEqualTo(3);
		assertThat(a.get("/api/wallet/recurring")).bodyJson()
			.extractingPath("$[0].nextDate")
			.isEqualTo(TODAY.plusWeeks(1).toString());
	}

	@Test
	void pauseAndResumeDoesNotMakeMissedDates() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String bills = walletCategoryId(a, "Bills");
		a.post("/api/wallet/recurring", recurringBody("Phone", bills, "MONTHLY", TODAY.plusDays(3)));
		String id = this.jdbc.queryForObject("SELECT id::text FROM wallet_recurring", String.class);

		assertThat(a.post("/api/wallet/recurring/" + id + "/pause", "")).bodyJson()
			.extractingPath("$.active")
			.isEqualTo(false);
		this.jdbc.update("UPDATE wallet_recurring SET start_date = ?, next_date = ?",
				java.sql.Date.valueOf(TODAY.minusMonths(2).plusDays(3)), java.sql.Date.valueOf(TODAY.minusMonths(2).plusDays(3)));
		assertThat(this.recurringJob.runDue()).isZero();

		MvcTestResult resumed = a.post("/api/wallet/recurring/" + id + "/resume", "");
		assertThat(resumed).bodyJson().extractingPath("$.active").isEqualTo(true);
		assertThat(walletCount("Phone")).isZero();
		assertThat(resumed).bodyJson().extractingPath("$.nextDate").isEqualTo(TODAY.plusDays(3).toString());

		assertThat(a.delete("/api/wallet/recurring/" + id)).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void monthlyOnThe31stUsesTheLastDayOfShortMonths() throws Exception {
		var method = WalletRecurring.class.getDeclaredMethod("nextOnOrAfter", LocalDate.class, String.class,
				int.class, LocalDate.class);
		method.setAccessible(true);
		LocalDate start = LocalDate.of(2026, 1, 31);
		assertThat(method.invoke(null, start, "MONTH", 1, LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(method.invoke(null, start, "MONTH", 1, LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 3, 31));
		assertThat(method.invoke(null, start, "YEAR", 1, LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2027, 1, 31));
		assertThat(method.invoke(null, start, "WEEK", 1, LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 2, 7));
		assertThat(method.invoke(null, start, "MONTH", 1, LocalDate.of(2025, 1, 1))).isEqualTo(start);
	}

	// ---- dashboard and alerts ----

	@Test
	void dashboardShowsBalanceMonthAndRecent() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = wallet("ada@example.com");
		String salary = walletCategoryId(a, "Salary");
		String food = walletCategoryId(a, "Food");
		a.post("/api/wallet/transactions",
				walletBody("INCOME", "2000", TODAY.minusMonths(2).toString(), "Old salary", salary, List.of()));
		addWallet(a, "INCOME", "1000.00", salary, "Salary");
		addWallet(a, "EXPENSE", "250.25", food, "Food");

		MvcTestResult dashboard = a.get("/api/wallet/dashboard");
		assertThat(dashboard).bodyJson().extractingPath("$.balance").isEqualTo("2749.75");
		if (YearMonth.from(TODAY.minusMonths(2)).isBefore(YearMonth.from(TODAY))) {
			assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.in").isEqualTo("1000.00");
		}
		assertThat(dashboard).bodyJson().extractingPath("$.thisMonth.out").isEqualTo("250.25");
		assertThat(dashboard).bodyJson().extractingPath("$.recent.length()").isEqualTo(3);
		assertThat(dashboard).bodyJson().extractingPath("$.budgets").isNotNull();
		assertThat(dashboard).bodyJson().extractingPath("$.goals").isNotNull();
	}

	@Test
	void alertsAreMineAndCanBeRead() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		insertAlert(ada, "one");
		insertAlert(ada, "two");
		String first = this.jdbc.queryForObject("SELECT id::text FROM notifications WHERE dedupe_key = 'one'",
				String.class);

		assertThat(a.get("/api/alerts")).bodyJson().extractingPath("$.unreadCount").isEqualTo(2);
		assertError(loggedIn("bob@example.com").post("/api/alerts/" + first + "/read", ""), HttpStatus.NOT_FOUND,
				"alert_not_found");
		assertThat(loggedIn("bob@example.com").get("/api/alerts")).bodyJson().extractingPath("$.items.length()")
			.isEqualTo(0);
		assertThat(a.post("/api/alerts/" + first + "/read", "")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/alerts")).bodyJson().extractingPath("$.unreadCount").isEqualTo(1);
		assertThat(a.post("/api/alerts/read-all", "")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.get("/api/alerts")).bodyJson().extractingPath("$.unreadCount").isEqualTo(0);
		assertThat(a.get("/api/alerts")).bodyJson().extractingPath("$.items[0].read").isEqualTo(true);
	}

	// ---- helpers ----

	/** Logged in with the wallet PIN set and unlocked. */
	private Client wallet(String email) {
		Client client = loggedIn(email);
		assertThat(client.post("/api/wallet/pin", "{\"pin\":\"" + PIN + "\"}")).hasStatus(HttpStatus.NO_CONTENT);
		return client;
	}

	private String walletCategoryId(Client client, String name) {
		MvcTestResult all = client.get("/api/wallet/categories/all");
		try {
			String body = all.getResponse().getContentAsString();
			int at = body.indexOf("\"name\":\"" + name + "\"");
			String before = body.substring(0, at);
			int idAt = before.lastIndexOf("\"id\":\"") + 6;
			return before.substring(idAt, before.indexOf('"', idAt));
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void addWallet(Client client, String type, String amount, String category, String description) {
		assertThat(client.post("/api/wallet/transactions", TransactionsIntegrationTests.confirmed(
				walletBody(type, amount, TODAY.toString(), description, category, List.of()))))
			.hasStatus(HttpStatus.CREATED);
	}

	private int walletCount(String description) {
		return this.jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE description = ?", Integer.class,
				description);
	}

	private List<String> alertKinds(Client client) {
		return this.jdbc.queryForList("SELECT kind FROM notifications ORDER BY created_at", String.class);
	}

	private void insertAlert(User user, String key) {
		this.jdbc.update("INSERT INTO notifications (id, user_id, kind, title, body, dedupe_key, created_at) "
				+ "VALUES (gen_random_uuid(), ?, 'BUDGET_80', 't', 'b', ?, now())", user.getId(), key);
	}

	private static String created(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		try {
			return result.getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String walletBody(String type, String amount, String date, String description,
			String categoryId, List<String> fileIds) {
		return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"date\":\""
				+ date + "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId
				+ "\",\"fileIds\":"
				+ fileIds.stream().map((id) -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]")) + "}";
	}

	private static String recurringBody(String name, String categoryId, String frequency, LocalDate start) {
		return "{\"name\":\"" + name + "\",\"type\":\"EXPENSE\",\"amount\":\"500\",\"categoryId\":\""
				+ categoryId + "\",\"description\":\"" + name + "\"," + repeatJson(frequency) + ",\"startDate\":\""
				+ start + "\"}";
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

}
