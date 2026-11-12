package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.logo.LogoFetcher;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional website on company and wallet transactions, and the cached logo (with a fake
 * fetcher: no network).
 */
class WebsiteAndLogoIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 'I', 'H',
			'D', 'R', 0, 0, 0, (byte) 180, 0, 0, 0, (byte) 180, 8, 6, 0, 0, 0 };

	@Autowired
	private FakeFetcher fetcher;

	@BeforeEach
	void resetFetcher() {
		this.fetcher.calls.clear();
		this.fetcher.logos.clear();
		this.fetcher.logos.put("claude.ai", new LogoFetcher.Logo("image/png", PNG));
		this.fetcher.logos.put("notion.so", new LogoFetcher.Logo("image/png", PNG));
		this.fetcher.logos.put("sub.example.com", new LogoFetcher.Logo("image/png", PNG));
		this.fetcher.logos.put("mine.example.com", new LogoFetcher.Logo("image/png", PNG));
	}

	@Test
	void companyWebsiteIsNormalizedLoggedAndShown() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");

		MvcTestResult created = a.post("/api/transactions", body(a, "https://www.Claude.ai/pricing", "Plan"));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.website").isEqualTo("claude.ai");
		assertThat(created).bodyJson().extractingPath("$.logoUrl").isEqualTo("/api/logos/claude.ai");
		String id = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		assertThat(this.jdbc.queryForObject("SELECT website FROM transactions", String.class)).isEqualTo("claude.ai");

		// In the list and the detail.
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].website").isEqualTo("claude.ai");
		assertThat(a.get("/api/transactions/" + id)).bodyJson().extractingPath("$.logoUrl")
			.isEqualTo("/api/logos/claude.ai");

		// History: created with the website, then a change.
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[0].changes[?(@.field == 'website')].to")
			.asArray()
			.containsExactly("claude.ai");
		String fileId = this.jdbc.queryForObject("SELECT id::text FROM files", String.class);
		MvcTestResult changed = a.put("/api/transactions/" + id, body(a, "google.com", "Plan", fileId));
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[0].changes")
			.asArray()
			.hasSize(1);
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[0].changes[0]")
			.isEqualTo(Map.of("field", "website", "from", "claude.ai", "to", "google.com"));

		// Removing it.
		MvcTestResult removed = a.put("/api/transactions/" + id, body(a, "", "Plan", fileId));
		assertThat(removed).bodyJson().extractingPath("$.website").isNull();
		assertThat(removed).bodyJson().extractingPath("$.logoUrl").isNull();

		// Bad input.
		MvcTestResult bad = a.post("/api/transactions", body(a, "http://127.0.0.1/admin", "Bad"));
		assertThat(bad).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(bad).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		assertThat(a.post("/api/transactions", body(a, "localhost", "Bad"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void logoIsFetchedOnceAndCached() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/transactions", body(a, "claude.ai", "Plan"))).hasStatus(HttpStatus.CREATED);

		MvcTestResult logo = a.get("/api/logos/claude.ai");
		assertThat(logo).hasStatus(HttpStatus.OK);
		assertThat(logo).hasContentType("image/png");
		assertThat(logo.getResponse().getContentAsByteArray()).isEqualTo(PNG);
		assertThat(logo).headers().hasValue("Cache-Control", "no-cache, private");
		String etag = logo.getResponse().getHeader("ETag");
		assertThat(etag).isNotBlank();
		assertThat(a.getIfNoneMatch("/api/logos/claude.ai", etag)).hasStatus(HttpStatus.NOT_MODIFIED);
		assertThat(logo).headers().hasValue("X-Content-Type-Options", "nosniff");
		assertThat(logo).headers().containsHeader("Content-Security-Policy");

		assertThat(a.get("/api/logos/claude.ai")).hasStatus(HttpStatus.OK);
		assertThat(this.fetcher.count("claude.ai")).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT status FROM logos WHERE domain = 'claude.ai'", String.class))
			.isEqualTo("OK");
	}

	@Test
	void noLogoIsCachedAndGivesNullUrl() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/transactions", body(a, "nologo.example.com", "Shop"))).hasStatus(HttpStatus.CREATED);

		MvcTestResult missing = a.get("/api/logos/nologo.example.com");
		assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(missing).bodyJson().extractingPath("$.error").isEqualTo("logo_not_found");
		assertThat(a.get("/api/logos/nologo.example.com")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.fetcher.count("nologo.example.com")).isEqualTo(1);

		// Now known: the frontend gets null and shows the category icon.
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].logoUrl").isNull();
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].website")
			.isEqualTo("nologo.example.com");
	}

	@Test
	void onlyDomainsInUseCanBeFetched() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.get("/api/logos/claude.ai")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(a.get("/api/logos/127.0.0.1")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(a.get("/api/logos/WWW.claude.ai")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.fetcher.calls).isEmpty();
		assertThat(client().get("/api/logos/claude.ai")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void walletWebsiteIsPrivateButWorks() {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		a.get("/api/wallet/categories/all");
		String food = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Food'",
				String.class);

		MvcTestResult created = a.post("/api/wallet/transactions",
				"{\"type\":\"EXPENSE\",\"amount\":\"9\",\"date\":\"" + TODAY
						+ "\",\"description\":\"Coffee\",\"website\":\"WWW.Claude.AI\",\"categoryId\":\"" + food
						+ "\",\"fileIds\":[]}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.website").isEqualTo("claude.ai");
		assertThat(created).bodyJson().extractingPath("$.logoUrl").isEqualTo("/api/logos/claude.ai");
		assertThat(a.get("/api/wallet/transactions")).bodyJson().extractingPath("$.items[0].website")
			.isEqualTo("claude.ai");
		assertThat(a.get("/api/logos/claude.ai")).hasStatus(HttpStatus.OK);

		// Another user cannot use my private domain to fetch or check logos, even with the
		// browser's cached copy (the check runs before the cache answer).
		String etag = a.get("/api/logos/claude.ai").getResponse().getHeader("ETag");
		Client bob = loggedIn("bob@example.com");
		assertThat(bob.get("/api/logos/claude.ai")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(bob.getIfNoneMatch("/api/logos/claude.ai", etag)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void companyCategoryWebsiteAndLogoPickingOrder() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("ada@example.com", Role.MEMBER);
		Client boss = loggedIn("boss@example.com");
		Client a = loggedIn("ada@example.com");

		// Parent with a website, child without.
		MvcTestResult parent = boss.post("/api/admin/categories",
				"{\"name\":\"Apps\",\"icon\":\"laptop\",\"website\":\"https://www.Notion.so/pricing\"}");
		assertThat(parent).hasStatus(HttpStatus.CREATED);
		assertThat(parent).bodyJson().extractingPath("$.website").isEqualTo("notion.so");
		assertThat(parent).bodyJson().extractingPath("$.logoUrl").isEqualTo("/api/logos/notion.so");
		String parentId = id("Apps");
		assertThat(boss.post("/api/admin/categories",
				"{\"name\":\"Tools\",\"icon\":\"wrench\",\"parentId\":\"" + parentId + "\"}"))
			.hasStatus(HttpStatus.CREATED);
		String childId = id("Tools");
		assertThat(boss.post("/api/admin/categories", "{\"name\":\"Bad\",\"icon\":\"laptop\",\"website\":\"localhost\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);

		// Pickers show it too.
		assertThat(a.get("/api/categories")).bodyJson()
			.extractingPath("$[?(@.name == 'Apps')].logoUrl")
			.asArray()
			.containsExactly("/api/logos/notion.so");
		assertThat(boss.get("/api/admin/categories")).bodyJson()
			.extractingPath("$[?(@.name == 'Apps')].website")
			.asArray()
			.containsExactly("notion.so");

		// Transaction in the child, no website: the parent's logo.
		assertThat(a.post("/api/transactions", bodyIn(a, "", "Licence", childId))).hasStatus(HttpStatus.CREATED);
		String tx = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.logoUrl")
			.isEqualTo("/api/logos/notion.so");
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.website").isNull();

		// Child gets its own website: the child's logo wins.
		MvcTestResult child = boss.patch("/api/admin/categories/" + childId, "{\"website\":\"sub.example.com\"}");
		assertThat(child).hasStatus(HttpStatus.OK);
		assertThat(child).bodyJson().extractingPath("$.website").isEqualTo("sub.example.com");
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].logoUrl")
			.isEqualTo("/api/logos/sub.example.com");

		// The transaction's own website wins over both.
		String file = this.jdbc.queryForObject("SELECT id::text FROM files", String.class);
		assertThat(a.put("/api/transactions/" + tx, bodyIn(a, "claude.ai", "Licence", childId, file))).bodyJson()
			.extractingPath("$.logoUrl")
			.isEqualTo("/api/logos/claude.ai");

		// Own website without a logo (known): falls back to the child's logo.
		assertThat(a.put("/api/transactions/" + tx, bodyIn(a, "nologo.example.com", "Licence", childId, file)))
			.hasStatus(HttpStatus.OK);
		assertThat(a.get("/api/logos/nologo.example.com")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.logoUrl")
			.isEqualTo("/api/logos/sub.example.com");

		// PATCH without website keeps it; null removes it; then the parent again.
		assertThat(boss.patch("/api/admin/categories/" + childId, "{\"name\":\"Tools 2\"}")).bodyJson()
			.extractingPath("$.website")
			.isEqualTo("sub.example.com");
		assertThat(boss.patch("/api/admin/categories/" + childId, "{\"website\":null}")).bodyJson()
			.extractingPath("$.website")
			.isNull();
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.logoUrl")
			.isEqualTo("/api/logos/notion.so");

		// Category-only domains can be fetched; employees cannot change categories.
		assertThat(a.get("/api/logos/notion.so")).hasStatus(HttpStatus.OK);
		assertThat(a.patch("/api/admin/categories/" + parentId, "{\"website\":\"x.com\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void walletCategoryWebsiteIsPrivate() {
		activeUser("ada@example.com", Role.MEMBER);
		activeUser("bob@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		a.get("/api/wallet/categories/all");
		String food = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Food'",
				String.class);

		MvcTestResult changed = a.patch("/api/wallet/categories/" + food, "{\"website\":\"WWW.mine.example.com/\"}");
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(changed).bodyJson().extractingPath("$.website").isEqualTo("mine.example.com");
		assertThat(changed).bodyJson().extractingPath("$.logoUrl").isEqualTo("/api/logos/mine.example.com");
		MvcTestResult sub = a.post("/api/wallet/categories", "{\"name\":\"Cafe\",\"icon\":\"coffee\",\"parentId\":\""
				+ food + "\",\"website\":\"\"}");
		assertThat(sub).hasStatus(HttpStatus.CREATED);
		assertThat(sub).bodyJson().extractingPath("$.website").isNull();
		String cafe = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Cafe'",
				String.class);

		MvcTestResult tx = a.post("/api/wallet/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"4\","
				+ "\"date\":\"" + TODAY + "\",\"description\":\"Latte\",\"categoryId\":\"" + cafe
				+ "\",\"fileIds\":[]}");
		assertThat(tx).hasStatus(HttpStatus.CREATED);
		assertThat(tx).bodyJson().extractingPath("$.logoUrl").isEqualTo("/api/logos/mine.example.com");
		assertThat(a.get("/api/wallet/categories")).bodyJson()
			.extractingPath("$[?(@.name == 'Food')].website")
			.asArray()
			.containsExactly("mine.example.com");
		assertThat(a.get("/api/logos/mine.example.com")).hasStatus(HttpStatus.OK);
		assertThat(loggedIn("bob@example.com").get("/api/logos/mine.example.com")).hasStatus(HttpStatus.NOT_FOUND);
	}

	private String id(String categoryName) {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = ?", String.class, categoryName);
	}

	private String bodyIn(Client client, String website, String description, String category, String... fileIds) {
		String file = (fileIds.length > 0) ? fileIds[0]
				: FilesIntegrationTests.uploadedId(client.upload("proof.pdf", "application/pdf", TestFiles.PDF));
		return "{\"type\":\"EXPENSE\",\"amount\":\"20\",\"date\":\"" + TODAY
				+ "\",\"description\":\"" + description + "\",\"website\":\"" + website + "\",\"categoryId\":\""
				+ category + "\",\"fileIds\":[\"" + file + "\"],\"confirmDuplicate\":true}";
	}

	private String body(Client client, String website, String description, String... fileIds) {
		String file = (fileIds.length > 0) ? fileIds[0]
				: FilesIntegrationTests.uploadedId(client.upload("proof.pdf", "application/pdf", TestFiles.PDF));
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Food'",
				String.class);
		return "{\"type\":\"EXPENSE\",\"amount\":\"20\",\"date\":\"" + TODAY
				+ "\",\"description\":\"" + description + "\",\"website\":\"" + website + "\",\"categoryId\":\""
				+ category + "\",\"fileIds\":[\"" + file + "\"],\"confirmDuplicate\":true}";
	}

	/** Returns the logos it was given; counts calls per domain. */
	static class FakeFetcher implements LogoFetcher {

		final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

		final Map<String, Logo> logos = new ConcurrentHashMap<>();

		@Override
		public Optional<Logo> fetch(String domain) {
			this.calls.add(domain);
			return Optional.ofNullable(this.logos.get(domain));
		}

		int count(String domain) {
			return (int) this.calls.stream().filter(domain::equals).count();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeFetcherConfig {

		@Bean
		@Primary
		FakeFetcher fakeLogoFetcher() {
			return new FakeFetcher();
		}

	}

}
