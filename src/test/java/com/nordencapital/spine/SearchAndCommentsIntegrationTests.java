package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 5, company side: filters and paging, subcategories, usage order, duplicate
 * warning, comments.
 */
class SearchAndCommentsIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	// ---- A. filters ----

	@Test
	void filtersCombineAndTotalsFollowThem() {
		// Ada is the owner: only the owner may filter by person.
		User ada = activeUser("ada@example.com", Role.OWNER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		Client b = loggedIn("bob@example.com");
		add(a, "EXPENSE", "100.00", "2026-03-05", "Taxi airport", "Transport");
		add(a, "EXPENSE", "20.00", "2026-03-06", "Lunch with team", "Food");
		incomeCategory("Refund");
		add(b, "INCOME", "500.00", "2026-03-07", "Refund taxi", "Refund");
		add(b, "EXPENSE", "300.00", "2026-04-01", "Hotel", "Travel");

		assertItems(a.get("/api/transactions?from=2026-03-01&to=2026-04-30&q=TAXI"), "Refund taxi", "Taxi airport");
		assertItems(a.get("/api/transactions?from=2026-03-01&to=2026-04-30&type=INCOME"), "Refund taxi");
		assertItems(a.get("/api/transactions?from=2026-03-01&to=2026-04-30&createdBy=" + ada.getId()), "Lunch with team",
				"Taxi airport");
		assertItems(a.get("/api/transactions?from=2026-03-01&to=2026-04-30&minAmount=20&maxAmount=100"),
				"Lunch with team", "Taxi airport");
		assertItems(a.get("/api/transactions?from=2026-03-06"), "Hotel", "Refund taxi", "Lunch with team");
		assertItems(a.get("/api/transactions?to=2026-03-05"), "Taxi airport");
		assertItems(a.get("/api/transactions?month=2026-04"), "Hotel");
		assertItems(a.get("/api/transactions?from=2026-03-01&to=2026-04-30&categoryIds=" + categoryId("Transport") + ","
				+ categoryId("Food") + "&type=EXPENSE"), "Lunch with team", "Taxi airport");

		MvcTestResult transport = a.get("/api/transactions?from=2026-03-01&to=2026-04-30&q=taxi");
		assertThat(transport).bodyJson().extractingPath("$.totals.in").isEqualTo("500.00");
		assertThat(transport).bodyJson().extractingPath("$.totals.out").isEqualTo("100.00");
		assertThat(transport).bodyJson().extractingPath("$.totals.net").isEqualTo("400.00");
	}

	@Test
	void pagingKeepsTotalsOfAllMatches() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		for (int i = 1; i <= 5; i++) {
			add(a, "EXPENSE", i + ".00", "2026-03-0" + i, "Item " + i, "Food");
		}
		MvcTestResult first = a.get("/api/transactions?month=2026-03&size=2");
		assertThat(first).bodyJson().extractingPath("$.items[*].description").asArray().containsExactly("Item 5", "Item 4");
		assertThat(first).bodyJson().extractingPath("$.page").isEqualTo(0);
		assertThat(first).bodyJson().extractingPath("$.size").isEqualTo(2);
		assertThat(first).bodyJson().extractingPath("$.totalItems").isEqualTo(5);
		assertThat(first).bodyJson().extractingPath("$.hasNext").isEqualTo(true);
		assertThat(first).bodyJson().extractingPath("$.totals.out").isEqualTo("15.00");

		MvcTestResult last = a.get("/api/transactions?month=2026-03&size=2&page=2");
		assertThat(last).bodyJson().extractingPath("$.items[*].description").asArray().containsExactly("Item 1");
		assertThat(last).bodyJson().extractingPath("$.hasNext").isEqualTo(false);
		assertThat(a.get("/api/transactions?month=2026-03")).bodyJson().extractingPath("$.size").isEqualTo(50);
	}

	@Test
	void searchFindsCommentsAndTreatsWildcardsAsText() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		String id = add(a, "EXPENSE", "10.00", "2026-03-05", "Coffee", "Food");
		add(a, "EXPENSE", "11.00", "2026-03-06", "Code A_1", "Food");
		add(a, "EXPENSE", "12.00", "2026-03-07", "Code AB1", "Food");
		a.post("/api/transactions/" + id + "/comments", "{\"text\":\"Meeting with the Atlas client\"}");

		assertItems(a.get("/api/transactions?month=2026-03&q=atlas"), "Coffee");
		// "_" is a plain character, not "any one character".
		assertItems(a.get("/api/transactions?month=2026-03&q=A_"), "Code A_1");
		assertItems(a.get("/api/transactions?month=2026-03&q=zzz"));
	}

	@Test
	void searchMatchesWebsiteAndSizeZeroOnlyCounts() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		add(a, "EXPENSE", "20.00", "2026-03-05", "Monthly plan", "Software");
		add(a, "EXPENSE", "30.00", "2026-03-06", "Lunch", "Food");
		this.jdbc.update("UPDATE transactions SET website = 'claude.ai' WHERE description = 'Monthly plan'");

		assertItems(a.get("/api/transactions?month=2026-03&q=CLAUDE"), "Monthly plan");
		assertItems(a.get("/api/transactions?month=2026-03&q=claude.ai"), "Monthly plan");

		MvcTestResult countOnly = a.get("/api/transactions?month=2026-03&size=0");
		assertThat(countOnly).hasStatus(HttpStatus.OK);
		assertThat(countOnly).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
		assertThat(countOnly).bodyJson().extractingPath("$.totalItems").isEqualTo(2);
		assertThat(countOnly).bodyJson().extractingPath("$.hasNext").isEqualTo(false);
		assertThat(countOnly).bodyJson().extractingPath("$.totals.out").isEqualTo("50.00");
	}

	@Test
	void badFiltersAreRefused() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		for (String bad : List.of("from=2026-02-30", "from=2026-05-01&to=2026-04-01", "type=GIFT",
				"createdBy=nope", "minAmount=1.234", "minAmount=10&maxAmount=5", "page=-1", "size=101", "size=-1",
				"categoryIds=x", "month=2026-13")) {
			MvcTestResult result = a.get("/api/transactions?" + bad);
			assertThat(result).as(bad).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		}
	}

	// ---- B. subcategories ----

	@Test
	void subcategoriesAreOneLevelAndParentIncludesChildren() {
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");
		String travel = categoryId("Travel");
		String flights = createdCategory(admin, "Flights", travel);
		assertThat(admin.get("/api/admin/categories")).bodyJson()
			.extractingPath("$[?(@.name=='Flights')].parentId")
			.asArray()
			.containsExactly(travel);

		assertValidation(admin.post("/api/admin/categories",
				"{\"name\":\"Deep\",\"icon\":\"plane\",\"parentId\":\"" + flights + "\"}"));
		assertValidation(admin.patch("/api/admin/categories/" + travel, "{\"parentId\":\"" + categoryId("Food") + "\"}"));
		assertValidation(admin.patch("/api/admin/categories/" + travel, "{\"parentId\":\"" + travel + "\"}"));
		assertValidation(admin.patch("/api/admin/categories/" + travel, "{\"parentId\":\"" + UUID.randomUUID() + "\"}"));
		assertValidation(admin.patch("/api/admin/categories/" + travel, "{\"parentId\":\"not-an-id\"}"));

		// Leave parentId out = keep; null = top level.
		assertThat(admin.patch("/api/admin/categories/" + flights, "{\"icon\":\"bus\"}")).bodyJson()
			.extractingPath("$.parentId")
			.isEqualTo(travel);
		assertThat(admin.patch("/api/admin/categories/" + flights, "{\"parentId\":null}")).bodyJson()
			.extractingPath("$.parentId")
			.isNull();
		assertThat(admin.patch("/api/admin/categories/" + flights, "{\"parentId\":\"" + travel + "\"}"))
			.hasStatus(HttpStatus.OK);

		add(admin, "EXPENSE", "250.00", "2026-03-05", "Flight", "Flights");
		add(admin, "EXPENSE", "80.00", "2026-03-06", "Train", "Travel");
		add(admin, "EXPENSE", "9.00", "2026-03-07", "Sandwich", "Food");
		assertItems(admin.get("/api/transactions?month=2026-03&categoryIds=" + travel), "Train", "Flight");
		assertItems(admin.get("/api/transactions?month=2026-03&categoryIds=" + flights), "Flight");
	}

	@Test
	void archivedParentHidesChildrenEverywhereNew() {
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");
		String travel = categoryId("Travel");
		createdCategory(admin, "Flights", travel);
		String oldTx = add(admin, "EXPENSE", "250.00", TODAY, "Flight", "Flights");
		admin.post("/api/admin/categories/" + travel + "/archive", "");

		assertThat(admin.get("/api/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.doesNotContain("Travel", "Flights");
		MvcTestResult refused = admin.post("/api/transactions",
				body("EXPENSE", "1.00", TODAY, "New flight", "Flights", List.of(upload(admin))));
		assertValidation(refused);
		// The old transaction keeps it.
		assertThat(admin.get("/api/transactions/" + oldTx)).bodyJson()
			.extractingPath("$.category.parentId")
			.isEqualTo(travel);
	}

	// ---- C. usage order ----

	@Test
	void pickersShowMostUsedFirst() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		add(a, "EXPENSE", "1.00", TODAY, "x1", "Travel");
		add(a, "EXPENSE", "2.00", TODAY, "x2", "Travel");
		add(a, "EXPENSE", "3.00", TODAY, "x3", "Office");
		// Older than 90 days: does not count.
		add(a, "EXPENSE", "4.00", LocalDate.now(ZoneOffset.UTC).minusDays(120).toString(), "old", "Bills");
		add(a, "EXPENSE", "5.00", LocalDate.now(ZoneOffset.UTC).minusDays(121).toString(), "old2", "Bills");

		MvcTestResult categories = a.get("/api/categories");
		assertThat(categories).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.startsWith("Travel", "Office", "Bills", "Food");
		assertThat(categories).bodyJson().extractingPath("$[0].usageCount").isEqualTo(2);
		assertThat(categories).bodyJson().extractingPath("$[2].usageCount").isEqualTo(0);

	}

	// ---- D. duplicates ----

	@Test
	void sameTypeAmountAndDateAsksForConfirmation() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		setName(ada, "Ada", "L");
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		String first = add(a, "EXPENSE", "42.00", TODAY, "Taxi", "Transport");

		Client b = loggedIn("bob@example.com");
		String body = body("EXPENSE", "42", TODAY, "Cab", "Travel", List.of(upload(b)));
		MvcTestResult duplicate = b.post("/api/transactions", body);
		assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
		assertThat(duplicate).bodyJson().extractingPath("$.error").isEqualTo("possible_duplicate");
		assertThat(duplicate).bodyJson().extractingPath("$.matches[0].id").isEqualTo(first);
		assertThat(duplicate).bodyJson().extractingPath("$.matches[0].description").isEqualTo("Taxi");
		assertThat(duplicate).bodyJson().extractingPath("$.matches[0].createdBy.name").isEqualTo("Ada L");
		assertThat(duplicate).bodyJson().extractingPath("$.matches[0].createdAt").isNotNull();

		assertThat(b.post("/api/transactions", TransactionsIntegrationTests.confirmed(body)))
			.hasStatus(HttpStatus.CREATED);
		// Other amount, type or date: no warning. Trashed: no warning.
		incomeCategory("Refund");
		assertThat(b.post("/api/transactions", body("INCOME", "42", TODAY, "x", "Refund", List.of(upload(b)))))
			.hasStatus(HttpStatus.CREATED);
		a.delete("/api/transactions/" + first);
		this.jdbc.update("UPDATE transactions SET deleted_at = now() WHERE type = 'EXPENSE'");
		assertThat(b.post("/api/transactions", body("EXPENSE", "42", TODAY, "x", "Food", List.of(upload(b)))))
			.hasStatus(HttpStatus.CREATED);
	}

	@Test
	void validationComesBeforeTheDuplicateWarning() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		add(a, "EXPENSE", "42.00", TODAY, "Taxi", "Transport");
		assertThat(a.post("/api/transactions", body("EXPENSE", "42", TODAY, "x", "Food", List.of())))
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("proof_required");
	}

	// ---- E. comments ----

	@Test
	void commentsRules() {
		activeUser("ada@example.com", Role.MEMBER);
		User bob = activeUser("bob@example.com", Role.MEMBER);
		activeUser("admin@example.com", Role.OWNER);
		Client a = loggedIn("ada@example.com");
		Client b = loggedIn("bob@example.com");
		String id = add(a, "EXPENSE", "10.00", TODAY, "Taxi", "Transport");

		MvcTestResult added = b.post("/api/transactions/" + id + "/comments", "{\"text\":\"  For the Atlas visit  \"}");
		assertThat(added).hasStatus(HttpStatus.CREATED);
		assertThat(added).bodyJson().extractingPath("$.text").isEqualTo("For the Atlas visit");
		assertThat(added).bodyJson().extractingPath("$.author.id").isEqualTo(bob.getId().toString());
		assertThat(added).bodyJson().extractingPath("$.canDelete").isEqualTo(true);
		String commentId = this.jdbc.queryForObject("SELECT id::text FROM transaction_comments", String.class);
		a.post("/api/transactions/" + id + "/comments", "{\"text\":\"Second\"}");

		MvcTestResult list = a.get("/api/transactions/" + id + "/comments");
		assertThat(list).bodyJson().extractingPath("$[*].text").asArray().containsExactly("For the Atlas visit", "Second");
		assertThat(list).bodyJson().extractingPath("$[0].canDelete").isEqualTo(false);
		assertThat(list).bodyJson().extractingPath("$[1].canDelete").isEqualTo(true);

		assertValidation(a.post("/api/transactions/" + id + "/comments", "{\"text\":\"   \"}"));
		assertValidation(a.post("/api/transactions/" + id + "/comments", "{\"text\":\"" + "x".repeat(501) + "\"}"));
		assertThat(a.delete("/api/transactions/" + id + "/comments/" + commentId)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(loggedIn("admin@example.com").delete("/api/transactions/" + id + "/comments/" + commentId))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(a.delete("/api/transactions/" + id + "/comments/" + commentId)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.error")
			.isEqualTo("comment_not_found");

		// Comments are not in the history.
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson().extractingPath("$.length()").isEqualTo(1);
		// Trashed transaction: 404.
		a.delete("/api/transactions/" + id);
		assertThat(a.get("/api/transactions/" + id + "/comments")).hasStatus(HttpStatus.NOT_FOUND);
	}

	// ---- helpers ----

	private String add(Client client, String type, String amount, String date, String description,
			String category) {
		MvcTestResult result = client.post("/api/transactions", TransactionsIntegrationTests
			.confirmed(body(type, amount, date, description, category, List.of(upload(client)))));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		try {
			return result.getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String createdCategory(Client admin, String name, String parentId) {
		MvcTestResult result = admin.post("/api/admin/categories",
				"{\"name\":\"" + name + "\",\"icon\":\"plane\",\"parentId\":\"" + parentId + "\"}");
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return categoryId(name);
	}

	private static String upload(Client client) {
		return FilesIntegrationTests.uploadedId(client.upload("proof.pdf", "application/pdf", TestFiles.PDF));
	}

	private String categoryId(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = ?", String.class, name);
	}

	private void setName(User user, String first, String last) {
		this.jdbc.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?", first, last, user.getId());
	}

	private String body(String type, String amount, String date, String description, String category,
			List<String> fileIds) {
		return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"date\":\""
				+ date + "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId(category)
				+ "\",\"fileIds\":" + ids(fileIds) + "}";
	}

	private static String ids(List<String> ids) {
		return ids.stream().map((id) -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	private static void assertItems(MvcTestResult result, String... descriptions) {
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.items[*].description").asArray().containsExactly(
				(Object[]) descriptions);
	}

	private static void assertValidation(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
	}

}
