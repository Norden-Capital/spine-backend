package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.ai.CommentSuggester;
import com.nordencapital.spine.user.Role;
import com.nordencapital.spine.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI suggestion with a key set, using a fake suggester (no real AI call): only the
 * allowed fields are sent, the hourly limit, and error mapping.
 */
@TestPropertySource(properties = { "app.ai.anthropic-api-key=test-key", "app.ai.max-per-hour=3" })
class AiSuggestionIntegrationTests extends IntegrationTestBase {

	@Autowired
	private FakeSuggester fake;

	@BeforeEach
	void resetFake() {
		this.fake.inputs.clear();
		this.fake.fail = false;
		this.fake.answer = Optional.of("Taxi to the airport for the client visit.");
	}

	@Test
	void suggestsForASavedTransactionWithOnlyAllowedFields() throws Exception {
		User ada = activeUser("ada.secret@example.com", Role.EMPLOYEE);
		this.jdbc.update("UPDATE users SET first_name = 'Ada', last_name = 'Secret' WHERE id = ?", ada.getId());
		Client client = loggedIn("ada.secret@example.com");
		assertThat(client.get("/api/features")).bodyJson().extractingPath("$.ai").isEqualTo(true);
		String id = createTransaction(client);

		MvcTestResult result = client.post("/api/ai/suggest-comment", "{\"transactionId\":\"" + id + "\"}");
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.text").isEqualTo("Taxi to the airport for the client visit.");

		CommentSuggester.Input sent = this.fake.inputs.get(0);
		assertThat(sent.type()).isEqualTo("EXPENSE");
		assertThat(sent.amount()).isEqualTo("42.50");
		assertThat(sent.currency()).isEqualTo("USD");
		assertThat(sent.description()).isEqualTo("Taxi to airport");
		assertThat(sent.categoryName()).isEqualTo("Transport");
		assertThat(sent.toString()).doesNotContain("secret").doesNotContain("Secret").doesNotContain("Ada")
			.doesNotContain("receipt.pdf");
	}

	@Test
	void suggestsForADraft() {
		activeUser("ada@example.com", Role.EMPLOYEE);
		Client client = loggedIn("ada@example.com");
		assertThat(client.post("/api/ai/suggest-comment",
				"{\"draft\":{\"description\":\"Team lunch\",\"amount\":\"80\",\"currency\":\"MAD\",\"tagNames\":[\"Q3\"]}}"))
			.hasStatus(HttpStatus.OK);
		assertThat(this.fake.inputs.get(0).tagNames()).containsExactly("Q3");
		assertThat(this.fake.inputs.get(0).description()).isEqualTo("Team lunch");
	}

	@Test
	void badRequestsAndErrors() {
		activeUser("ada@example.com", Role.EMPLOYEE);
		Client client = loggedIn("ada@example.com");
		assertError(client.post("/api/ai/suggest-comment", "{}"), HttpStatus.BAD_REQUEST, "validation_error");
		assertError(client.post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"\"}}"), HttpStatus.BAD_REQUEST,
				"validation_error");
		assertError(client.post("/api/ai/suggest-comment", "{\"transactionId\":\"" + java.util.UUID.randomUUID() + "\"}"),
				HttpStatus.NOT_FOUND, "transaction_not_found");

		this.fake.fail = true;
		assertError(client.post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"x\"}}"), HttpStatus.BAD_GATEWAY,
				"ai_unavailable");
		this.fake.fail = false;
		this.fake.answer = Optional.empty();
		assertError(client.post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"x\"}}"), HttpStatus.BAD_GATEWAY,
				"ai_unavailable");
	}

	@Test
	void limitedPerUserPerHour() {
		activeUser("ada@example.com", Role.EMPLOYEE);
		activeUser("bob@example.com", Role.EMPLOYEE);
		Client ada = loggedIn("ada@example.com");
		for (int i = 0; i < 3; i++) {
			assertThat(ada.post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"x\"}}"))
				.hasStatus(HttpStatus.OK);
		}
		assertError(ada.post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"x\"}}"),
				HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
		assertThat(loggedIn("bob@example.com").post("/api/ai/suggest-comment", "{\"draft\":{\"description\":\"x\"}}"))
			.hasStatus(HttpStatus.OK);
	}

	private String createTransaction(Client client) throws Exception {
		String file = FilesIntegrationTests
			.uploadedId(client.upload("receipt.pdf", "application/pdf", TestFiles.PDF));
		String category = this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = 'Transport'",
				String.class);
		MvcTestResult created = client.post("/api/transactions",
				"{\"type\":\"EXPENSE\",\"amount\":\"42.5\",\"currency\":\"USD\",\"date\":\""
						+ LocalDate.now(ZoneOffset.UTC) + "\",\"description\":\"Taxi to airport\",\"categoryId\":\""
						+ category + "\",\"tagIds\":[],\"fileIds\":[\"" + file + "\"]}");
		assertThat(created).hasStatus(HttpStatus.CREATED);
		return this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

	/** Records what would be sent to the AI. */
	static class FakeSuggester implements CommentSuggester {

		final List<Input> inputs = new CopyOnWriteArrayList<>();

		volatile boolean fail;

		volatile Optional<String> answer = Optional.empty();

		@Override
		public Optional<String> suggest(Input input) {
			this.inputs.add(input);
			if (this.fail) {
				throw new AiUnavailableException("down", null);
			}
			return this.answer;
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeAiConfig {

		@Bean
		@Primary
		FakeSuggester fakeSuggester() {
			return new FakeSuggester();
		}

	}

}
