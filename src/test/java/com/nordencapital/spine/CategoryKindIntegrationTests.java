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
 * Categories split by kind (EXPENSE / INCOME): subcategories follow their main, a
 * transaction or template must fit its category, old ones are kept on edit, filters.
 */
class CategoryKindIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Test
	void companyKindsAndTransactions() {
		activeUser("boss@example.com", Role.OWNER);
		Client b = loggedIn("boss@example.com");

		MvcTestResult grants = b.post("/api/admin/categories",
				"{\"name\":\"Grants\",\"icon\":\"landmark\",\"kind\":\"INCOME\"}");
		assertThat(grants).hasStatus(HttpStatus.CREATED);
		assertThat(grants).bodyJson().extractingPath("$.kind").isEqualTo("INCOME");
		String grantsId = id("Grants");
		MvcTestResult sub = b.post("/api/admin/categories",
				"{\"name\":\"EU grants\",\"icon\":\"globe\",\"parentId\":\"" + grantsId + "\"}");
		assertThat(sub).bodyJson().extractingPath("$.kind").isEqualTo("INCOME");
		assertInvalid(b.post("/api/admin/categories", "{\"name\":\"Bad\",\"icon\":\"globe\",\"kind\":\"EXPENSE\","
				+ "\"parentId\":\"" + grantsId + "\"}"), "subcategory always has the kind");
		assertInvalid(b.post("/api/admin/categories", "{\"name\":\"Bad\",\"icon\":\"globe\",\"kind\":\"GIFT\"}"), "kind");
		assertThat(b.post("/api/admin/categories", "{\"name\":\"Plain\",\"icon\":\"globe\"}")).bodyJson()
			.extractingPath("$.kind")
			.isEqualTo("EXPENSE");

		// Filters and kind in responses.
		assertThat(b.get("/api/categories?kind=INCOME")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactlyInAnyOrder("Grants", "EU grants");
		assertThat(b.get("/api/admin/categories?kind=EXPENSE")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.contains("Food")
			.doesNotContain("Grants");
		assertThat(b.get("/api/categories")).bodyJson().extractingPath("$.length()").isEqualTo(10);
		assertThat(b.get("/api/categories?kind=GIFT")).hasStatus(HttpStatus.BAD_REQUEST);

		// The category must fit the type.
		assertInvalid(b.post("/api/transactions", tx(b, "EXPENSE", "Grants", "Gift")), "income category");
		assertInvalid(b.post("/api/transactions", tx(b, "INCOME", "Food", "Gift")), "expense category");
		MvcTestResult income = b.post("/api/transactions", tx(b, "INCOME", "EU grants", "Grant Q1"));
		assertThat(income).hasStatus(HttpStatus.CREATED);
		assertThat(income).bodyJson().extractingPath("$.category.kind").isEqualTo("INCOME");
		String txId = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		String file = this.jdbc.queryForObject("SELECT id::text FROM files WHERE transaction_id = ?::uuid",
				String.class, txId);

		// A subcategory cannot change kind on its own; the main can, and its subs follow.
		assertInvalid(b.patch("/api/admin/categories/" + id("EU grants"), "{\"kind\":\"EXPENSE\"}"),
				"subcategory always has the kind");
		assertThat(b.patch("/api/admin/categories/" + grantsId, "{\"kind\":\"EXPENSE\"}")).bodyJson()
			.extractingPath("$.kind")
			.isEqualTo("EXPENSE");
		assertThat(this.jdbc.queryForObject("SELECT kind FROM categories WHERE name = 'EU grants'", String.class))
			.isEqualTo("EXPENSE");

		// The old INCOME transaction keeps its (now expense) category when other fields change...
		assertThat(b.put("/api/transactions/" + txId, txWith(file, "INCOME", "EU grants", "Grant Q1 (paid)")))
			.hasStatus(HttpStatus.OK);
		// ...but a new category or type must fit.
		assertInvalid(b.put("/api/transactions/" + txId, txWith(file, "INCOME", "Food", "Grant")), "expense category");
		assertThat(b.put("/api/transactions/" + txId, txWith(file, "EXPENSE", "EU grants", "Grant"))).hasStatus(
				HttpStatus.OK);

		// Moving under a main of another kind is refused (PATCH and drag and drop).
		incomeCategory("Salary");
		assertInvalid(b.patch("/api/admin/categories/" + id("EU grants"), "{\"parentId\":\"" + id("Salary") + "\"}"),
				"main category is an income category");
		assertInvalid(b.patch("/api/admin/categories/" + id("Plain") + "/move",
				"{\"parentId\":\"" + id("Salary") + "\",\"position\":0}"), "main category is an income category");
		assertThat(b.patch("/api/admin/categories/" + id("Plain") + "/move",
				"{\"parentId\":\"" + id("Food") + "\",\"position\":0}")).hasStatus(HttpStatus.OK);

		// Repeat templates too.
		assertInvalid(b.post("/api/admin/recurring", "{\"type\":\"INCOME\",\"amount\":\"10\","
				+ "\"description\":\"x\",\"categoryId\":\"" + id("Food") + "\",\"unit\":\"MONTH\",\"every\":1,\"startDate\":\""
				+ TODAY + "\"}"), "expense category");
	}

	@Test
	void walletKinds() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		String food = walletId("Food");
		String salary = walletId("Salary");
		assertThat(a.get("/api/wallet/categories/all?kind=INCOME")).bodyJson()
			.extractingPath("$[*].kind")
			.asArray()
			.containsOnly("INCOME");

		assertInvalid(a.post("/api/wallet/transactions", walletTx("INCOME", food)), "expense category");
		MvcTestResult ok = a.post("/api/wallet/transactions", walletTx("INCOME", salary));
		assertThat(ok).hasStatus(HttpStatus.CREATED);
		assertThat(ok).bodyJson().extractingPath("$.category.kind").isEqualTo("INCOME");

		MvcTestResult bonus = a.post("/api/wallet/categories",
				"{\"name\":\"Bonus\",\"icon\":\"gift\",\"parentId\":\"" + salary + "\"}");
		assertThat(bonus).bodyJson().extractingPath("$.kind").isEqualTo("INCOME");
		assertInvalid(a.patch("/api/wallet/categories/" + walletId("Bonus") + "/move",
				"{\"parentId\":\"" + food + "\",\"position\":0}"), "main category is an expense category");

		assertInvalid(a.post("/api/wallet/recurring", "{\"name\":\"Pay\",\"type\":\"EXPENSE\",\"amount\":\"10\","
				+ "\"categoryId\":\"" + salary + "\",\"description\":\"x\",\"unit\":\"MONTH\",\"every\":1,"
				+ "\"startDate\":\"" + TODAY + "\"}"), "income category");
	}

	private String id(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = ?", String.class, name);
	}

	private String walletId(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = ?", String.class, name);
	}

	private String tx(Client client, String type, String category, String description) {
		String file = FilesIntegrationTests.uploadedId(client.upload("p.pdf", "application/pdf", TestFiles.PDF));
		return txWith(file, type, category, description);
	}

	private String txWith(String file, String type, String category, String description) {
		return "{\"type\":\"" + type + "\",\"amount\":\"100\",\"date\":\"" + TODAY
				+ "\",\"description\":\"" + description + "\",\"categoryId\":\"" + id(category) + "\",\"fileIds\":[\""
				+ file + "\"],\"confirmDuplicate\":true}";
	}

	private static String walletTx(String type, String category) {
		return "{\"type\":\"" + type + "\",\"amount\":\"100\",\"date\":\"" + TODAY
				+ "\",\"description\":\"x\",\"categoryId\":\"" + category + "\",\"fileIds\":[]}";
	}

	private static void assertInvalid(MvcTestResult result, String messagePart) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		assertThat(result).bodyJson().extractingPath("$.message").asString().contains(messagePart);
	}

}
