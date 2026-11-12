package com.nordencapital.spine;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drag and drop of categories: manual position, main/sub moves, one level only,
 * manage-page order. Company (OWNER) and wallet (each user, private).
 */
class CategoryMoveIntegrationTests extends IntegrationTestBase {

	@Test
	void companyMovesAndOrder() {
		activeUser("boss@example.com", Role.OWNER);
		Client boss = loggedIn("boss@example.com");
		String travel = id("Travel");
		create(boss, "Flights", travel);
		create(boss, "Hotels", travel);

		// New subcategories go last in their group; the list shows each main then its subs.
		assertThat(positionsUnder(travel)).containsExactly("Flights", "Hotels");
		assertThat(names(boss.get("/api/admin/categories"))).containsSubsequence("Travel", "Flights", "Hotels");

		// Main category to the top.
		MvcTestResult moved = boss.patch("/api/admin/categories/" + id("Food") + "/move",
				"{\"parentId\":null,\"position\":0}");
		assertThat(moved).hasStatus(HttpStatus.OK);
		assertThat(names(moved).get(0)).isEqualTo("Food");
		assertThat(positionsUnder(null).get(0)).isEqualTo("Food");
		assertContiguous(null);

		// Reorder inside a group.
		assertThat(boss.patch("/api/admin/categories/" + id("Hotels") + "/move",
				"{\"parentId\":\"" + travel + "\",\"position\":0}")).hasStatus(HttpStatus.OK);
		assertThat(positionsUnder(travel)).containsExactly("Hotels", "Flights");

		// Sub -> main (left out parentId = main), position too big = last; old group renumbered.
		assertThat(boss.patch("/api/admin/categories/" + id("Flights") + "/move", "{\"position\":99}"))
			.hasStatus(HttpStatus.OK);
		List<String> mains = positionsUnder(null);
		assertThat(mains.get(mains.size() - 1)).isEqualTo("Flights");
		assertThat(positionsUnder(travel)).containsExactly("Hotels");
		assertContiguous(null);
		assertContiguous(travel);

		// Main without subs -> sub.
		String food = id("Food");
		assertThat(boss.patch("/api/admin/categories/" + id("Bills") + "/move",
				"{\"parentId\":\"" + food + "\",\"position\":0}")).hasStatus(HttpStatus.OK);
		assertThat(positionsUnder(food)).containsExactly("Bills");
		MvcTestResult list = boss.get("/api/admin/categories");
		assertThat(names(list)).containsSubsequence("Food", "Bills");
		assertThat(list).bodyJson().extractingPath("$[?(@.name == 'Bills')].position").asArray().containsExactly(0);

		// One level only, with clear messages.
		assertInvalid(boss.patch("/api/admin/categories/" + travel + "/move", "{\"parentId\":\"" + food
				+ "\",\"position\":0}"), "has subcategories");
		assertInvalid(boss.patch("/api/admin/categories/" + food + "/move", "{\"parentId\":\"" + id("Bills")
				+ "\",\"position\":0}"), "already a subcategory");
		assertInvalid(boss.patch("/api/admin/categories/" + id("Office") + "/move", "{\"parentId\":\"" + id("Office")
				+ "\",\"position\":0}"), "own parent");
		assertInvalid(boss.patch("/api/admin/categories/" + id("Office") + "/move", "{\"position\":-1}"), "position");
		assertInvalid(boss.patch("/api/admin/categories/" + id("Office") + "/move", "{\"parentId\":null}"), "position");
		assertThat(boss.patch("/api/admin/categories/" + UUID.randomUUID() + "/move", "{\"position\":0}"))
			.hasStatus(HttpStatus.NOT_FOUND);

		// Archived ones keep working (and can be a parent here).
		String office = id("Office");
		boss.post("/api/admin/categories/" + office + "/archive", "");
		assertThat(boss.patch("/api/admin/categories/" + office + "/move", "{\"position\":0}"))
			.hasStatus(HttpStatus.OK);
		assertThat(boss.patch("/api/admin/categories/" + id("Hotels") + "/move",
				"{\"parentId\":\"" + office + "\",\"position\":0}")).hasStatus(HttpStatus.OK);
		assertThat(positionsUnder(office)).containsExactly("Hotels");
	}

	@Test
	void onlyOwnerAndAdminMoveCompanyCategories() {
		activeUser("emp@example.com", Role.MEMBER);
		activeUser("owner@example.com", Role.OWNER);
		assertThat(loggedIn("emp@example.com").patch("/api/admin/categories/" + id("Food") + "/move",
				"{\"position\":0}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(client().patch("/api/admin/categories/" + id("Food") + "/move", "{\"position\":0}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(loggedIn("owner@example.com").patch("/api/admin/categories/" + id("Food") + "/move",
				"{\"position\":0}")).hasStatus(HttpStatus.OK);
	}

	@Test
	void walletMovesArePrivate() {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client ada = wallet("ada@example.com");
		Client bob = wallet("bob@example.com");
		String food = walletId("ada@example.com", "Food");
		assertThat(ada.post("/api/wallet/categories",
				"{\"name\":\"Cafe\",\"icon\":\"coffee\",\"parentId\":\"" + food + "\"}")).hasStatus(HttpStatus.CREATED);

		// Starting categories: expenses then income, by name.
		MvcTestResult all = ada.get("/api/wallet/categories/all");
		assertThat(names(all)).containsExactly("Bills", "Food", "Cafe", "Fun", "Health", "Other", "Shopping",
				"Transport", "Client payment", "Investment", "Other income", "Refund", "Salary");

		MvcTestResult moved = ada.patch("/api/wallet/categories/" + walletId("ada@example.com", "Salary") + "/move",
				"{\"parentId\":null,\"position\":0}");
		assertThat(moved).hasStatus(HttpStatus.OK);
		assertThat(names(moved).get(0)).isEqualTo("Salary");

		// Food has Cafe: it cannot go under another category.
		MvcTestResult refused = ada.patch("/api/wallet/categories/" + food + "/move",
				"{\"parentId\":\"" + walletId("ada@example.com", "Fun") + "\",\"position\":0}");
		assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(refused).bodyJson().extractingPath("$.message").asString().contains("has subcategories");

		// Cafe -> main category at position 1.
		assertThat(ada.patch("/api/wallet/categories/" + walletId("ada@example.com", "Cafe") + "/move",
				"{\"parentId\":null,\"position\":1}")).hasStatus(HttpStatus.OK);
		assertThat(names(ada.get("/api/wallet/categories/all")).subList(0, 3)).containsExactly("Salary", "Cafe",
				"Bills");

		// Someone else's category: not found; my list is unchanged by them.
		assertThat(bob.patch("/api/wallet/categories/" + food + "/move", "{\"position\":0}"))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(bob.patch("/api/wallet/categories/" + walletId("bob@example.com", "Food") + "/move",
				"{\"parentId\":\"" + food + "\",\"position\":0}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(names(bob.get("/api/wallet/categories/all")).get(0)).isEqualTo("Bills");
	}

	private Client wallet(String email) {
		Client client = loggedIn(email);
		assertThat(client.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		return client;
	}

	private void create(Client admin, String name, String parentId) {
		assertThat(admin.post("/api/admin/categories",
				"{\"name\":\"" + name + "\",\"icon\":\"plane\",\"parentId\":\"" + parentId + "\"}"))
			.hasStatus(HttpStatus.CREATED);
	}

	private String id(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = ?", String.class, name);
	}

	private String walletId(String email, String name) {
		return this.jdbc.queryForObject("SELECT c.id::text FROM wallet_categories c JOIN users u ON u.id = c.user_id "
				+ "WHERE u.email = ? AND c.name = ?", String.class, email, name);
	}

	/** Names of the company categories under a parent (null = mains), by position. */
	private List<String> positionsUnder(String parentId) {
		return (parentId == null)
				? this.jdbc.queryForList("SELECT name FROM categories WHERE parent_id IS NULL ORDER BY position",
						String.class)
				: this.jdbc.queryForList("SELECT name FROM categories WHERE parent_id = ?::uuid ORDER BY position",
						String.class, parentId);
	}

	/** Positions in a group are 0, 1, 2, ... */
	private void assertContiguous(String parentId) {
		List<Integer> positions = (parentId == null)
				? this.jdbc.queryForList("SELECT position FROM categories WHERE parent_id IS NULL ORDER BY position",
						Integer.class)
				: this.jdbc.queryForList("SELECT position FROM categories WHERE parent_id = ?::uuid ORDER BY position",
						Integer.class, parentId);
		for (int i = 0; i < positions.size(); i++) {
			assertThat(positions.get(i)).isEqualTo(i);
		}
	}

	private static List<String> names(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.OK);
		try {
			return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$[*].name");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void assertInvalid(MvcTestResult result, String messagePart) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		assertThat(result).bodyJson().extractingPath("$.message").asString().contains(messagePart);
	}

}
