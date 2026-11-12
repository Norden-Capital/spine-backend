package com.nordencapital.spine;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Categories and tags: who can read, add, change, archive.
 */
class CategoriesAndTagsIntegrationTests extends IntegrationTestBase {

	// ---- categories ----

	@Test
	void seededCategoriesInDisplayOrder() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		MvcTestResult result = loggedIn("emp@example.com").get("/api/categories");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Travel", "Food", "Transport", "Software", "Office", "Bills", "Other");
		assertThat(result).bodyJson()
			.extractingPath("$[*].icon")
			.asArray()
			.containsExactly("plane", "utensils", "car", "laptop", "briefcase", "receipt", "ellipsis");
		assertThat(result).bodyJson().extractingPath("$[0].archived").isEqualTo(false);
		assertThat(result).bodyJson().extractingPath("$[0].id").isNotNull();
	}

	@Test
	void iconListIsExposed() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		MvcTestResult result = loggedIn("emp@example.com").get("/api/categories/icons");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(50);
		assertThat(result).bodyJson().extractingPath("$[0]").isEqualTo("plane");
		assertThat(result).bodyJson().extractingPath("$[49]").isEqualTo("ellipsis");
		// Every seeded category icon is allowed.
		assertThat(result).bodyJson()
			.extractingPath("$")
			.asArray()
			.contains("plane", "utensils", "car", "laptop", "briefcase", "receipt", "ellipsis");
	}

	@Test
	void anonymousGets401() {
		assertThat(client().get("/api/categories")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(client().get("/api/tags")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void employeeCannotUseAdminCategoriesOrTags() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client employee = loggedIn("emp@example.com");
		String food = categoryId("Food");

		assertForbidden(employee.get("/api/admin/categories"));
		assertForbidden(employee.post("/api/admin/categories", "{\"name\":\"Gym\",\"icon\":\"zap\"}"));
		assertForbidden(employee.patch("/api/admin/categories/" + food, "{\"name\":\"Meals\"}"));
		assertForbidden(employee.post("/api/admin/categories/" + food + "/archive", ""));
		assertForbidden(employee.get("/api/admin/tags"));
		String tag = createdTagId(employee, "client-x");
		assertForbidden(employee.patch("/api/admin/tags/" + tag, "{\"name\":\"client-y\"}"));
		assertForbidden(employee.post("/api/admin/tags/" + tag + "/archive", ""));
	}

	@Test
	void adminCreatesCategoryBeforeOther() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");

		MvcTestResult created = admin.post("/api/admin/categories", "{\"name\":\"  Gym  \",\"icon\":\"heart-pulse\"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Gym");
		assertThat(created).bodyJson().extractingPath("$.icon").isEqualTo("heart-pulse");
		assertThat(created).bodyJson().extractingPath("$.archived").isEqualTo(false);

		assertThat(admin.get("/api/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Travel", "Food", "Transport", "Software", "Office", "Bills", "Gym", "Other");
	}

	@Test
	void ownerCanManageCategoriesToo() {
		activeUser("owner@example.com", Role.OWNER);
		Client owner = loggedIn("owner@example.com");
		assertThat(owner.post("/api/admin/categories", "{\"name\":\"Gym\",\"icon\":\"zap\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertThat(owner.get("/api/admin/categories")).hasStatus(HttpStatus.OK);
	}

	@Test
	void categoryNamesAreUniqueIgnoringCase() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");

		assertConflict(admin.post("/api/admin/categories", "{\"name\":\"food\",\"icon\":\"coffee\"}"), "name_taken");
		assertConflict(admin.patch("/api/admin/categories/" + categoryId("Travel"), "{\"name\":\"FOOD\"}"),
				"name_taken");

		// Renaming a category to itself with other case is fine.
		assertThat(admin.patch("/api/admin/categories/" + categoryId("Food"), "{\"name\":\"FOOD\"}"))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo("FOOD");
	}

	@Test
	void categoryInputIsValidated() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		String food = categoryId("Food");

		assertValidation(admin.post("/api/admin/categories", "{\"name\":\"   \",\"icon\":\"zap\"}"));
		assertValidation(admin.post("/api/admin/categories", "{\"name\":\"" + "x".repeat(41) + "\",\"icon\":\"zap\"}"));
		assertValidation(admin.post("/api/admin/categories", "{\"name\":\"Gym\",\"icon\":\"rocket\"}"));
		assertValidation(admin.post("/api/admin/categories", "{\"name\":\"Gym\"}"));
		assertValidation(admin.post("/api/admin/categories", "{\"name\":\"A\\tB\",\"icon\":\"zap\"}"));
		assertValidation(admin.patch("/api/admin/categories/" + food, "{\"name\":\"  \"}"));
		assertValidation(admin.patch("/api/admin/categories/" + food, "{\"icon\":\"rocket\"}"));
		assertThat(admin.post("/api/admin/categories", "{\"name\":\"" + "x".repeat(40) + "\",\"icon\":\"zap\"}"))
			.hasStatus(HttpStatus.CREATED);
	}

	@Test
	void patchChangesOnlyGivenFields() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		String food = categoryId("Food");

		MvcTestResult icon = admin.patch("/api/admin/categories/" + food, "{\"icon\":\"coffee\"}");
		assertThat(icon).hasStatus(HttpStatus.OK);
		assertThat(icon).bodyJson().extractingPath("$.name").isEqualTo("Food");
		assertThat(icon).bodyJson().extractingPath("$.icon").isEqualTo("coffee");

		MvcTestResult name = admin.patch("/api/admin/categories/" + food, "{\"name\":\"Meals\"}");
		assertThat(name).bodyJson().extractingPath("$.name").isEqualTo("Meals");
		assertThat(name).bodyJson().extractingPath("$.icon").isEqualTo("coffee");

		assertNotFound(admin.patch("/api/admin/categories/" + UUID.randomUUID(), "{\"name\":\"X\"}"),
				"category_not_found");
	}

	@Test
	void archivedCategoryIsHiddenButKept() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		String bills = categoryId("Bills");

		assertThat(admin.post("/api/admin/categories/" + bills + "/archive", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.archived")
			.isEqualTo(true);
		assertThat(admin.post("/api/admin/categories/" + bills + "/archive", "")).hasStatus(HttpStatus.OK);
		assertThat(admin.get("/api/categories")).bodyJson().extractingPath("$[*].name").asArray().doesNotContain("Bills");
		assertThat(admin.get("/api/admin/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.contains("Bills");
		// The name stays taken while archived.
		assertConflict(admin.post("/api/admin/categories", "{\"name\":\"bills\",\"icon\":\"zap\"}"), "name_taken");

		assertThat(admin.post("/api/admin/categories/" + bills + "/unarchive", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.archived")
			.isEqualTo(false);
		assertThat(admin.get("/api/categories")).bodyJson().extractingPath("$[*].name").asArray().contains("Bills");
		assertNotFound(admin.post("/api/admin/categories/" + UUID.randomUUID() + "/archive", ""),
				"category_not_found");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class)).isEqualTo(7);
	}

	@Test
	void categoryWritesNeedCsrf() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		MvcTestResult result = admin.putWithoutCsrf("/api/admin/categories/" + categoryId("Food"), "{}");
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("csrf_invalid");
	}

	// ---- tags ----

	@Test
	void anyUserCanAddATagAndGetsTheSameOneBack() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		activeUser("emp2@example.com", Role.EMPLOYEE);
		Client employee = loggedIn("emp@example.com");

		MvcTestResult created = employee.post("/api/tags", "{\"name\":\"  Client X  \"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Client X");
		assertThat(created).bodyJson().extractingPath("$.archived").isEqualTo(false);
		String id = this.jdbc.queryForObject("SELECT id FROM tags", String.class);
		assertThat(created).bodyJson().extractingPath("$.id").isEqualTo(id);

		MvcTestResult again = loggedIn("emp2@example.com").post("/api/tags", "{\"name\":\"client x\"}");
		assertThat(again).hasStatus(HttpStatus.OK);
		assertThat(again).bodyJson().extractingPath("$.id").isEqualTo(id);
		assertThat(again).bodyJson().extractingPath("$.name").isEqualTo("Client X");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM tags", Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT u.email FROM tags t JOIN users u ON u.id = t.created_by", String.class))
			.isEqualTo("emp@example.com");
	}

	@Test
	void archivedTagCannotBeAddedAgain() {
		activeUser("admin@example.com", Role.ADMIN);
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client admin = loggedIn("admin@example.com");
		String id = createdTagId(admin, "Old project");
		assertThat(admin.post("/api/admin/tags/" + id + "/archive", "")).hasStatus(HttpStatus.OK);

		assertConflict(loggedIn("emp@example.com").post("/api/tags", "{\"name\":\"OLD PROJECT\"}"), "tag_archived");

		assertThat(admin.post("/api/admin/tags/" + id + "/unarchive", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.archived")
			.isEqualTo(false);
		assertThat(loggedIn("emp@example.com").post("/api/tags", "{\"name\":\"old project\"}"))
			.hasStatus(HttpStatus.OK);
	}

	@Test
	void tagSearchIsCaseInsensitiveSortedAndSkipsArchived() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		createdTagId(admin, "zeta-Client");
		createdTagId(admin, "Alpha client");
		createdTagId(admin, "Internal");
		String archived = createdTagId(admin, "Client archived");
		admin.post("/api/admin/tags/" + archived + "/archive", "");

		assertThat(admin.get("/api/tags?q=CLIENT")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Alpha client", "zeta-Client");
		assertThat(admin.get("/api/tags")).bodyJson().extractingPath("$.length()").isEqualTo(3);
		assertThat(admin.get("/api/tags?q=%25")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(admin.get("/api/tags?q=" + "x".repeat(41))).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(0);
	}

	@Test
	void tagSearchReturnsAtMost50() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		for (int i = 0; i < 55; i++) {
			this.jdbc.update("INSERT INTO tags (id, name, archived, created_at, updated_at) VALUES (?, ?, false, now(), now())",
					UUID.randomUUID(), String.format("tag-%02d", i));
		}
		MvcTestResult result = loggedIn("emp@example.com").get("/api/tags?q=tag");
		assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(50);
		assertThat(result).bodyJson().extractingPath("$[0].name").isEqualTo("tag-00");
	}

	@Test
	void adminTagListShowsArchivedAndCreatedAt() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		createdTagId(admin, "b-tag");
		String archived = createdTagId(admin, "a-tag");
		admin.post("/api/admin/tags/" + archived + "/archive", "");

		MvcTestResult list = admin.get("/api/admin/tags");
		assertThat(list).hasStatus(HttpStatus.OK);
		assertThat(list).bodyJson().extractingPath("$[*].name").asArray().containsExactly("a-tag", "b-tag");
		assertThat(list).bodyJson().extractingPath("$[0].archived").isEqualTo(true);
		assertThat(list).bodyJson().extractingPath("$[0].createdAt").isNotNull();
	}

	@Test
	void tagRenameRules() {
		activeUser("admin@example.com", Role.ADMIN);
		Client admin = loggedIn("admin@example.com");
		String one = createdTagId(admin, "One");
		createdTagId(admin, "Two");

		assertConflict(admin.patch("/api/admin/tags/" + one, "{\"name\":\"two\"}"), "name_taken");
		assertThat(admin.patch("/api/admin/tags/" + one, "{\"name\":\" ONE \"}")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.name")
			.isEqualTo("ONE");
		assertValidation(admin.patch("/api/admin/tags/" + one, "{\"name\":\"\"}"));
		assertValidation(admin.patch("/api/admin/tags/" + one, "{\"name\":\"" + "y".repeat(41) + "\"}"));
		assertNotFound(admin.patch("/api/admin/tags/" + UUID.randomUUID(), "{\"name\":\"Z\"}"), "tag_not_found");
		assertNotFound(admin.post("/api/admin/tags/" + UUID.randomUUID() + "/archive", ""), "tag_not_found");
	}

	@Test
	void tagInputIsValidated() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client employee = loggedIn("emp@example.com");
		assertValidation(employee.post("/api/tags", "{\"name\":\"  \"}"));
		assertValidation(employee.post("/api/tags", "{}"));
		assertValidation(employee.post("/api/tags", "{\"name\":\"" + "y".repeat(41) + "\"}"));
		assertValidation(employee.post("/api/tags", "{\"name\":\"a\\nb\"}"));
	}

	private String categoryId(String name) {
		return this.jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", String.class, name);
	}

	private String createdTagId(Client client, String name) {
		MvcTestResult result = client.post("/api/tags", "{\"name\":\"" + name + "\"}");
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return this.jdbc.queryForObject("SELECT id FROM tags WHERE name = ?", String.class, name);
	}

	private static void assertForbidden(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("forbidden");
	}

	private static void assertValidation(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
	}

	private static void assertConflict(MvcTestResult result, String code) {
		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

	private static void assertNotFound(MvcTestResult result, String code) {
		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

}
