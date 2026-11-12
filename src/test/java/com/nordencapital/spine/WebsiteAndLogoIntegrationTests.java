package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Optional website on company and wallet transactions and categories, and the logo URLs
 * the browser tries (declared icon, /apple-touch-icon.png, /favicon.ico), with a fake
 * page reader: no network.
 */
class WebsiteAndLogoIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Autowired
	private FakeFetcher fetcher;

	@BeforeEach
	void resetFetcher() {
		this.fetcher.calls.clear();
		this.fetcher.icons.clear();
		this.fetcher.unreadable.clear();
		for (String domain : List.of("claude.ai", "notion.so", "sub.example.com", "mine.example.com")) {
			this.fetcher.icons.put(domain, declared(domain));
		}
	}

	@Test
	void companyWebsiteIsNormalizedLoggedAndShown() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");

		MvcTestResult created = a.post("/api/transactions", body(a, "https://www.Claude.ai/pricing", "Plan"));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.website").isEqualTo("claude.ai");
		// Right away the browser already gets the two standard places to try.
		assertThat(created).bodyJson().extractingPath("$.logoUrls").asArray().containsAll(fallbacks("claude.ai"));
		String id = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		assertThat(this.jdbc.queryForObject("SELECT website FROM transactions", String.class)).isEqualTo("claude.ai");

		// Once the page was read, the declared icon comes first.
		awaitLookup("claude.ai");
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].website").isEqualTo("claude.ai");
		assertThat(a.get("/api/transactions/" + id)).bodyJson().extractingPath("$.logoUrls").asArray()
			.containsExactlyElementsOf(urls("claude.ai"));

		// History: created with the website, then a change.
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[0].changes[?(@.field == 'website')].to")
			.asArray()
			.containsExactly("claude.ai");
		String fileId = this.jdbc.queryForObject("SELECT id::text FROM files", String.class);
		MvcTestResult changed = a.put("/api/transactions/" + id, body(a, "google.com", "Plan", fileId));
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(a.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[0].changes[0]")
			.isEqualTo(Map.of("field", "website", "from", "claude.ai", "to", "google.com"));

		// Removing it: no URLs, the category icon is shown.
		MvcTestResult removed = a.put("/api/transactions/" + id, body(a, "", "Plan", fileId));
		assertThat(removed).bodyJson().extractingPath("$.website").isNull();
		assertThat(removed).bodyJson().extractingPath("$.logoUrls").asArray().isEmpty();

		// Bad input.
		MvcTestResult bad = a.post("/api/transactions", body(a, "http://127.0.0.1/admin", "Bad"));
		assertThat(bad).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(bad).bodyJson().extractingPath("$.error").isEqualTo("validation_error");
		assertThat(a.post("/api/transactions", body(a, "localhost", "Bad"))).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void pageIsReadOnceAndCachedAndTheImageIsNeverServedByUs() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/transactions", body(a, "claude.ai", "Plan"))).hasStatus(HttpStatus.CREATED);
		awaitLookup("claude.ai");
		for (int i = 0; i < 3; i++) {
			a.get("/api/transactions");
		}
		assertThat(this.fetcher.count("claude.ai")).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT icon_url FROM logos WHERE domain = 'claude.ai'", String.class))
			.isEqualTo(declared("claude.ai"));
		// The old image endpoint is gone: the browser loads logos from the brand's site.
		assertThat(a.get("/api/logos/claude.ai")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void blockedPageStillGivesTheStandardUrlsAndIsTriedAgainLater() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		this.fetcher.unreadable.add("blocked.example.com");
		assertThat(a.post("/api/transactions", body(a, "blocked.example.com", "Shop"))).hasStatus(HttpStatus.CREATED);
		awaitLookup("blocked.example.com");

		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].logoUrls").asArray()
			.containsExactlyElementsOf(fallbacks("blocked.example.com"));
		assertThat(this.jdbc.queryForObject("SELECT status FROM logos WHERE domain = 'blocked.example.com'",
				String.class)).isEqualTo("NONE");
		assertThat(this.jdbc.queryForObject("SELECT retry_after < now() + interval '25 hours' FROM logos "
				+ "WHERE domain = 'blocked.example.com'", Boolean.class)).isTrue();

		// Due again: the next list looks it up once more.
		this.fetcher.unreadable.clear();
		this.fetcher.icons.put("blocked.example.com", declared("blocked.example.com"));
		this.jdbc.update("UPDATE logos SET retry_after = now() - interval '1 second'");
		a.get("/api/transactions");
		awaitCalls("blocked.example.com", 2);
		awaitIcon("blocked.example.com");
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].logoUrls").asArray()
			.containsExactlyElementsOf(urls("blocked.example.com"));
	}

	@Test
	void onlyWebUrlsAreEverGiven() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		this.fetcher.icons.put("evil.example.com", "javascript:alert(1)");
		this.fetcher.icons.put("inline.example.com", "data:image/png;base64,AAAA");
		assertThat(a.post("/api/transactions", body(a, "evil.example.com", "One"))).hasStatus(HttpStatus.CREATED);
		assertThat(a.post("/api/transactions", body(a, "inline.example.com", "Two"))).hasStatus(HttpStatus.CREATED);
		awaitLookup("evil.example.com");
		awaitLookup("inline.example.com");
		MvcTestResult list = a.get("/api/transactions");
		assertThat(list).bodyJson().extractingPath("$.items[*].logoUrls[*]").asArray()
			.allSatisfy((url) -> assertThat(url.toString()).startsWith("https://"))
			.containsAll(fallbacks("evil.example.com"))
			.containsAll(fallbacks("inline.example.com"));
		assertThat(this.jdbc.queryForList("SELECT icon_url FROM logos", String.class)).containsOnlyNulls();
	}

	@Test
	void walletWebsiteWorks() {
		activeUser("ada@example.com", Role.MEMBER);
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
		awaitLookup("claude.ai");
		assertThat(a.get("/api/wallet/transactions")).bodyJson().extractingPath("$.items[0].logoUrls").asArray()
			.containsExactlyElementsOf(urls("claude.ai"));
	}

	@Test
	void companyCategoryWebsiteAndLogoOrder() {
		activeUser("boss@example.com", Role.OWNER);
		activeUser("ada@example.com", Role.MEMBER);
		Client boss = loggedIn("boss@example.com");
		Client a = loggedIn("ada@example.com");

		// Parent with a website, child without.
		MvcTestResult parent = boss.post("/api/admin/categories",
				"{\"name\":\"Apps\",\"icon\":\"laptop\",\"website\":\"https://www.Notion.so/pricing\"}");
		assertThat(parent).hasStatus(HttpStatus.CREATED);
		assertThat(parent).bodyJson().extractingPath("$.website").isEqualTo("notion.so");
		String parentId = id("Apps");
		assertThat(boss.post("/api/admin/categories",
				"{\"name\":\"Tools\",\"icon\":\"wrench\",\"parentId\":\"" + parentId + "\"}"))
			.hasStatus(HttpStatus.CREATED);
		String childId = id("Tools");
		assertThat(boss.post("/api/admin/categories", "{\"name\":\"Bad\",\"icon\":\"laptop\",\"website\":\"localhost\"}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		awaitLookup("notion.so");

		// Pickers show it too.
		assertThat(a.get("/api/categories")).bodyJson()
			.extractingPath("$[?(@.name == 'Apps')].logoUrls[0]")
			.asArray()
			.containsExactly(declared("notion.so"));

		// Transaction in the child, no website: the parent's URLs.
		assertThat(a.post("/api/transactions", bodyIn(a, "", "Licence", childId))).hasStatus(HttpStatus.CREATED);
		String tx = this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.logoUrls").asArray()
			.containsExactlyElementsOf(urls("notion.so"));

		// Child gets its own website: the child's URLs first, then the parent's.
		assertThat(boss.patch("/api/admin/categories/" + childId, "{\"website\":\"sub.example.com\"}"))
			.hasStatus(HttpStatus.OK);
		awaitLookup("sub.example.com");
		assertThat(a.get("/api/transactions")).bodyJson().extractingPath("$.items[0].logoUrls").asArray()
			.containsExactlyElementsOf(join(urls("sub.example.com"), urls("notion.so")));

		// The transaction's own website comes before both.
		String file = this.jdbc.queryForObject("SELECT id::text FROM files", String.class);
		assertThat(a.put("/api/transactions/" + tx, bodyIn(a, "claude.ai", "Licence", childId, file)))
			.hasStatus(HttpStatus.OK);
		awaitLookup("claude.ai");
		assertThat(a.get("/api/transactions/" + tx)).bodyJson().extractingPath("$.logoUrls").asArray()
			.containsExactlyElementsOf(join(urls("claude.ai"), urls("sub.example.com"), urls("notion.so")));

		// PATCH without website keeps it; null removes it.
		assertThat(boss.patch("/api/admin/categories/" + childId, "{\"name\":\"Tools 2\"}")).bodyJson()
			.extractingPath("$.website")
			.isEqualTo("sub.example.com");
		assertThat(boss.patch("/api/admin/categories/" + childId, "{\"website\":null}")).bodyJson()
			.extractingPath("$.logoUrls")
			.asArray()
			.isEmpty();
		assertThat(a.patch("/api/admin/categories/" + parentId, "{\"website\":\"x.com\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void walletCategoryWebsite() {
		activeUser("ada@example.com", Role.MEMBER);
		Client a = loggedIn("ada@example.com");
		assertThat(a.post("/api/wallet/pin", "{\"pin\":\"123456\"}")).hasStatus(HttpStatus.NO_CONTENT);
		a.get("/api/wallet/categories/all");
		String food = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Food'",
				String.class);

		MvcTestResult changed = a.patch("/api/wallet/categories/" + food, "{\"website\":\"WWW.mine.example.com/\"}");
		assertThat(changed).hasStatus(HttpStatus.OK);
		assertThat(changed).bodyJson().extractingPath("$.website").isEqualTo("mine.example.com");
		MvcTestResult sub = a.post("/api/wallet/categories", "{\"name\":\"Cafe\",\"icon\":\"coffee\",\"parentId\":\""
				+ food + "\",\"website\":\"\"}");
		assertThat(sub).hasStatus(HttpStatus.CREATED);
		assertThat(sub).bodyJson().extractingPath("$.website").isNull();
		String cafe = this.jdbc.queryForObject("SELECT id::text FROM wallet_categories WHERE name = 'Cafe'",
				String.class);
		awaitLookup("mine.example.com");

		MvcTestResult tx = a.post("/api/wallet/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"4\","
				+ "\"date\":\"" + TODAY + "\",\"description\":\"Latte\",\"categoryId\":\"" + cafe
				+ "\",\"fileIds\":[]}");
		assertThat(tx).hasStatus(HttpStatus.CREATED);
		assertThat(tx).bodyJson().extractingPath("$.logoUrls").asArray()
			.containsExactlyElementsOf(urls("mine.example.com"));
		assertThat(a.get("/api/wallet/categories")).bodyJson()
			.extractingPath("$[?(@.name == 'Food')].website")
			.asArray()
			.containsExactly("mine.example.com");
	}

	// ---- helpers ----

	private static String declared(String domain) {
		return "https://cdn." + domain + "/icon-180.png";
	}

	private static List<String> fallbacks(String domain) {
		return List.of("https://" + domain + "/apple-touch-icon.png", "https://" + domain + "/favicon.ico");
	}

	/** Declared icon, then the two standard places. */
	private static List<String> urls(String domain) {
		return join(List.of(declared(domain)), fallbacks(domain));
	}

	@SafeVarargs
	private static List<String> join(List<String>... lists) {
		List<String> all = new ArrayList<>();
		for (List<String> list : lists) {
			all.addAll(list);
		}
		return all;
	}

	/** The background lookup has stored its result. */
	private void awaitLookup(String domain) {
		for (int i = 0; i < 100; i++) {
			Integer rows = this.jdbc.queryForObject("SELECT count(*) FROM logos WHERE domain = ?", Integer.class, domain);
			if (rows != null && rows > 0) {
				return;
			}
			sleep();
		}
		throw new AssertionError("no lookup for " + domain);
	}

	private void awaitIcon(String domain) {
		for (int i = 0; i < 100; i++) {
			List<String> icon = this.jdbc.queryForList("SELECT icon_url FROM logos WHERE domain = ? AND icon_url IS NOT NULL",
					String.class, domain);
			if (!icon.isEmpty()) {
				return;
			}
			sleep();
		}
		throw new AssertionError("no icon for " + domain);
	}

	private void awaitCalls(String domain, int calls) {
		for (int i = 0; i < 100 && this.fetcher.count(domain) < calls; i++) {
			sleep();
		}
		assertThat(this.fetcher.count(domain)).isEqualTo(calls);
	}

	private static void sleep() {
		try {
			Thread.sleep(50);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
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

	/** Returns the declared icons it was given; counts calls per domain. */
	static class FakeFetcher implements LogoFetcher {

		final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

		final Map<String, String> icons = new ConcurrentHashMap<>();

		final java.util.Set<String> unreadable = ConcurrentHashMap.newKeySet();

		@Override
		public Lookup lookUp(String domain) {
			this.calls.add(domain);
			if (this.unreadable.contains(domain)) {
				return new Lookup(false, null, "HTTP 403");
			}
			String icon = this.icons.get(domain);
			return new Lookup(true, icon, (icon == null) ? "no icon" : null);
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
