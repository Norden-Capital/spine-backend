package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drawn faces: default seed from the user id (never the email), PATCH /api/me/avatar,
 * and the seed on every place that shows a user.
 */
class AvatarIntegrationTests extends IntegrationTestBase {

	@Test
	void defaultSeedComesFromTheIdAndCanBeChanged() {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		String id = ada.getId().toString();

		MvcTestResult me = a.get("/api/me");
		assertThat(me).bodyJson().extractingPath("$.avatarSeed").isEqualTo(id);
		assertThat(me).bodyJson().extractingPath("$.avatarIsDefault").isEqualTo(true);
		assertThat(a.get("/api/auth/me")).bodyJson().extractingPath("$.avatarSeed").isEqualTo(id);

		MvcTestResult changed = a.patch("/api/me/avatar", "{\"seed\":\"Felix-42\"}");
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(changed).bodyJson().extractingPath("$.avatarSeed").isEqualTo("Felix-42");
		assertThat(changed).bodyJson().extractingPath("$.avatarIsDefault").isEqualTo(false);
		assertThat(a.get("/api/auth/me")).bodyJson().extractingPath("$.avatarSeed").isEqualTo("Felix-42");

		for (String bad : new String[] { "\"\"", "\"a b\"", "\"<svg>\"", "\"" + "x".repeat(65) + "\"", "\"é\"" }) {
			MvcTestResult result = a.patch("/api/me/avatar", "{\"seed\":" + bad + "}");
			assertThat(result).as(bad).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		}
		assertThat(a.get("/api/me")).bodyJson().extractingPath("$.avatarSeed").isEqualTo("Felix-42");

		// null = back to the default.
		assertThat(a.patch("/api/me/avatar", "{\"seed\":null}")).bodyJson().extractingPath("$.avatarSeed").isEqualTo(id);
		assertThat(this.jdbc.queryForObject("SELECT avatar_seed FROM users WHERE id = ?", String.class, ada.getId()))
			.isNull();
		assertThat(client().patch("/api/me/avatar", "{\"seed\":\"x\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void everyUserShownCarriesItsSeed() throws Exception {
		User ada = activeUser("ada@example.com", Role.MEMBER);
		User boss = activeUser("boss@example.com", Role.OWNER);
		Client a = loggedIn("ada@example.com");
		Client b = loggedIn("boss@example.com");
		a.patch("/api/me/avatar", "{\"seed\":\"ada-face\"}");

		String file = FilesIntegrationTests.uploadedId(a.upload("r.pdf", "application/pdf", TestFiles.PDF));
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'", String.class);
		MvcTestResult created = a.post("/api/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"5\","
				+ "\"date\":\"" + LocalDate.now(ZoneOffset.UTC) + "\",\"description\":\"Lunch\",\"categoryId\":\""
				+ category + "\",\"fileIds\":[\"" + file + "\"]}");
		assertThat(created).bodyJson().extractingPath("$.createdBy.avatarSeed").isEqualTo("ada-face");
		String tx = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);

		b.post("/api/transactions/" + tx + "/comments", "{\"text\":\"ok\"}");
		assertThat(a.get("/api/transactions/" + tx + "/comments")).bodyJson()
			.extractingPath("$[0].author.avatarSeed")
			.isEqualTo(boss.getId().toString());
		assertThat(a.get("/api/transactions/" + tx + "/history")).bodyJson()
			.extractingPath("$[0].by.avatarSeed")
			.isEqualTo("ada-face");

		assertThat(b.get("/api/admin/users")).bodyJson()
			.extractingPath("$[?(@.email == 'ada@example.com')].avatarSeed")
			.asArray()
			.containsExactly("ada-face");
		assertThat(b.get("/api/admin/users/" + ada.getId())).bodyJson().extractingPath("$.avatarSeed")
			.isEqualTo("ada-face");

		// Nothing personal in a default seed.
		assertThat(b.get("/api/me")).bodyJson().extractingPath("$.avatarSeed").asString().doesNotContain("boss");
	}

}
