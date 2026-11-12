package com.nordencapital.spine;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Categories: who can read, add, change, archive.
 */
class CategoriesIntegrationTests extends IntegrationTestBase {

	// ---- categories ----

	@Test
	void seededCategoriesUnusedAreByName() {
		activeUser("emp@example.com", Role.MEMBER);
		MvcTestResult result = loggedIn("emp@example.com").get("/api/categories");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Bills", "Food", "Office", "Other", "Software", "Transport", "Travel");
		assertThat(result).bodyJson()
			.extractingPath("$[*].icon")
			.asArray()
			.containsExactly("receipt", "utensils", "briefcase", "ellipsis", "laptop", "car", "plane");
		assertThat(result).bodyJson().extractingPath("$[0].usageCount").isEqualTo(0);
		assertThat(result).bodyJson().extractingPath("$[0].parentId").isNull();
		assertThat(result).bodyJson().extractingPath("$[0].archived").isEqualTo(false);
		assertThat(result).bodyJson().extractingPath("$[0].id").isNotNull();
	}

	@Test
	void iconListIsExposed() {
		activeUser("emp@example.com", Role.MEMBER);
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
	}

	@Test
	void employeeCannotUseAdminCategories() {
		activeUser("emp@example.com", Role.MEMBER);
		Client employee = loggedIn("emp@example.com");
		String food = categoryId("Food");

		assertForbidden(employee.get("/api/admin/categories"));
		assertForbidden(employee.post("/api/admin/categories", "{\"name\":\"Gym\",\"icon\":\"zap\"}"));
		assertForbidden(employee.patch("/api/admin/categories/" + food, "{\"name\":\"Meals\"}"));
		assertForbidden(employee.post("/api/admin/categories/" + food + "/archive", ""));
	}

	@Test
	void adminCreatesCategory() {
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");

		MvcTestResult created = admin.post("/api/admin/categories", "{\"name\":\"  Gym  \",\"icon\":\"heart-pulse\"}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Gym");
		assertThat(created).bodyJson().extractingPath("$.icon").isEqualTo("heart-pulse");
		assertThat(created).bodyJson().extractingPath("$.archived").isEqualTo(false);

		assertThat(admin.get("/api/categories")).bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Bills", "Food", "Gym", "Office", "Other", "Software", "Transport", "Travel");
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
		activeUser("admin@example.com", Role.OWNER);
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
		activeUser("admin@example.com", Role.OWNER);
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
		activeUser("admin@example.com", Role.OWNER);
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
		activeUser("admin@example.com", Role.OWNER);
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
		activeUser("admin@example.com", Role.OWNER);
		Client admin = loggedIn("admin@example.com");
		MvcTestResult result = admin.putWithoutCsrf("/api/admin/categories/" + categoryId("Food"), "{}");
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("csrf_invalid");
	}

	private String categoryId(String name) {
		return this.jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", String.class, name);
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
