package com.nordencapital.spine;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
 * Company transactions: shared editing, proof files, validation, history, trash,
 * restore, totals and the month filter.
 */
class TransactionsIntegrationTests extends IntegrationTestBase {

	private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

	@Test
	void createAndRead() {
		User emp = activeUser("emp@example.com", Role.MEMBER);
		setName(emp, "Ada", "Lovelace");
		Client client = loggedIn("emp@example.com");
		String file = upload(client, "receipt.pdf");

		MvcTestResult created = client.post("/api/transactions",
				body("EXPENSE", "123.4", TODAY, "  Taxi to airport ", "Transport", List.of(file)));
		assertThat(created).hasStatus(HttpStatus.CREATED);
		assertThat(created).bodyJson().extractingPath("$.type").isEqualTo("EXPENSE");
		assertThat(created).bodyJson().extractingPath("$.amount").isEqualTo("123.40");
		assertThat(created).bodyJson().doesNotHavePath("$.currency");
		assertThat(created).bodyJson().extractingPath("$.date").isEqualTo(TODAY);
		assertThat(created).bodyJson().extractingPath("$.description").isEqualTo("Taxi to airport");
		assertThat(created).bodyJson().extractingPath("$.category.name").isEqualTo("Transport");
		assertThat(created).bodyJson().extractingPath("$.category.icon").isEqualTo("car");
		assertThat(created).bodyJson().extractingPath("$.category.archived").isEqualTo(false);
		assertThat(created).bodyJson().doesNotHavePath("$.tags");
		assertThat(created).bodyJson().extractingPath("$.files[0].name").isEqualTo("receipt.pdf");
		assertThat(created).bodyJson().extractingPath("$.files[0].contentType").isEqualTo("application/pdf");
		assertThat(created).bodyJson().extractingPath("$.createdBy.name").isEqualTo("Ada Lovelace");
		assertThat(created).bodyJson().extractingPath("$.updatedBy").isNull();
		assertThat(created).bodyJson().extractingPath("$.updatedAt").isNull();
		assertThat(created).bodyJson().extractingPath("$.canDelete").isEqualTo(true);
		String id = onlyTransactionId();

		assertThat(client.get("/api/transactions/" + id)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.amount")
			.isEqualTo("123.40");
		assertThat(this.jdbc.queryForObject("SELECT transaction_id::text FROM files", String.class)).isEqualTo(id);

		MvcTestResult history = client.get("/api/transactions/" + id + "/history");
		assertThat(history).bodyJson().extractingPath("$[0].action").isEqualTo("CREATED");
		assertThat(history).bodyJson().extractingPath("$[0].by.name").isEqualTo("Ada Lovelace");
		assertThat(history).bodyJson().extractingPath("$[0].changes[?(@.field=='amount')].to").asArray()
			.containsExactly("123.40");
		assertThat(history).bodyJson().extractingPath("$[0].changes[?(@.field=='category')].to").asArray()
			.containsExactly("Transport");
		assertThat(history).bodyJson().extractingPath("$[0].changes[?(@.field=='files')].to").asArray()
			.containsExactly("receipt.pdf");
		assertThat(history).bodyJson().extractingPath("$[0].changes[0].from").isNull();
	}

	@Test
	void membersSeeEverythingButChangeOnlyTheirOwnAndTheOwnerChangesAll() {
		User owner = activeUser("owner@example.com", Role.OWNER);
		activeUser("emp@example.com", Role.MEMBER);
		User other = activeUser("other@example.com", Role.MEMBER);
		Client author = loggedIn("emp@example.com");
		String file = upload(author, "r.pdf");
		author.post("/api/transactions", body("EXPENSE", "10", TODAY, "Coffee", "Food", List.of(file)));
		String id = onlyTransactionId();
		assertThat(author.get("/api/transactions/" + id)).bodyJson().extractingPath("$.canEdit").isEqualTo(true);

		// Another member: sees it, cannot change, trash or swap its proof.
		Client colleague = loggedIn("other@example.com");
		MvcTestResult list = colleague.get("/api/transactions");
		assertThat(list).bodyJson().extractingPath("$.items.length()").isEqualTo(1);
		assertThat(list).bodyJson().extractingPath("$.items[0].canEdit").isEqualTo(false);
		assertThat(list).bodyJson().extractingPath("$.items[0].canDelete").isEqualTo(false);
		assertError(colleague.put("/api/transactions/" + id,
				body("EXPENSE", "12.50", TODAY, "Coffee x2", "Food", List.of(file))), HttpStatus.FORBIDDEN, "forbidden");
		String theirs = upload(colleague, "theirs.pdf");
		assertError(colleague.put("/api/transactions/" + id,
				body("EXPENSE", "10", TODAY, "Coffee", "Food", List.of(theirs))), HttpStatus.FORBIDDEN, "forbidden");
		assertError(colleague.delete("/api/transactions/" + id), HttpStatus.FORBIDDEN, "forbidden");
		// ...but can comment, and copy it (a copy is a new transaction of their own).
		assertThat(colleague.post("/api/transactions/" + id + "/comments", "{\"text\":\"Thanks\"}"))
			.hasStatus(HttpStatus.CREATED);
		assertThat(colleague.post("/api/transactions", confirmed(body("EXPENSE", "10", TODAY, "Coffee", "Food",
				List.of(upload(colleague, "copy.pdf")))))).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		// Only the owner filters by person.
		assertError(colleague.get("/api/transactions?createdBy=" + other.getId()), HttpStatus.FORBIDDEN, "forbidden");

		// The owner changes and trashes anyone's.
		Client boss = loggedIn("owner@example.com");
		MvcTestResult edited = boss.put("/api/transactions/" + id,
				body("EXPENSE", "12.50", TODAY, "Coffee x2", "Food", List.of(file)));
		assertThat(edited).hasStatus(HttpStatus.OK);
		assertThat(edited).bodyJson().extractingPath("$.amount").isEqualTo("12.50");
		assertThat(edited).bodyJson().extractingPath("$.updatedBy.id").isEqualTo(owner.getId().toString());
		assertThat(edited).bodyJson().extractingPath("$.updatedAt").isNotNull();
		assertThat(edited).bodyJson().extractingPath("$.canEdit").isEqualTo(true);
		assertThat(boss.get("/api/transactions?createdBy=" + other.getId())).hasStatus(HttpStatus.OK);
		assertThat(boss.delete("/api/transactions/" + id)).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void proofFilesAreRequiredAndLimited() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");

		assertError(client.post("/api/transactions", body("EXPENSE", "10", TODAY, "No proof", "Food", List.of())), "proof_required");
		assertError(client.post("/api/transactions", body("EXPENSE", "10", TODAY, "No proof", "Food", null)), "proof_required");
		List<String> six = List.of(upload(client, "1.pdf"), upload(client, "2.pdf"), upload(client, "3.pdf"),
				upload(client, "4.pdf"), upload(client, "5.pdf"), upload(client, "6.pdf"));
		assertError(client.post("/api/transactions", body("EXPENSE", "10", TODAY, "Six", "Food", six)),
				"validation_error");
		assertThat(client.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Five", "Food", six.subList(0, 5))))
			.hasStatus(HttpStatus.CREATED);
	}

	@Test
	void filesMustBeMyNewUploadsOrAlreadyOnIt() {
		activeUser("emp@example.com", Role.MEMBER);
		activeUser("other@example.com", Role.MEMBER);
		Client me = loggedIn("emp@example.com");
		Client other = loggedIn("other@example.com");
		String othersUpload = upload(other, "theirs.pdf");
		assertError(me.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Steal", "Food", List.of(othersUpload))),
				"validation_error");

		String mine = upload(me, "mine.pdf");
		me.post("/api/transactions", body("EXPENSE", "10", TODAY, "First", "Food", List.of(mine)));
		// The same file cannot be put on a second transaction.
		assertError(me.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Second", "Food", List.of(mine))), "validation_error");
		assertError(me.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Unknown", "Food", List.of(UUID.randomUUID().toString()))),
				"validation_error");
	}

	@Test
	void fieldsAreValidated() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		String file = upload(client, "r.pdf");
		List<String> files = List.of(file);

		for (String amount : List.of("0", "0.00", "-1", "1.234", "abc", "12,50", "99999999999", "1e5")) {
			assertError(client.post("/api/transactions", body("EXPENSE", amount, TODAY, "x", "Food", files)), "validation_error");
		}
		assertError(client.post("/api/transactions", body("GIFT", "1", TODAY, "x", "Food", files)),
				"validation_error");
		assertError(client.post("/api/transactions",
				body("EXPENSE", "1", TODAY, "   ", "Food", files)), "validation_error");
		assertError(client.post("/api/transactions",
				body("EXPENSE", "1", TODAY, "x".repeat(201), "Food", files)), "validation_error");
		assertError(client.post("/api/transactions",
				body("EXPENSE", "1", "2026-02-30", "x", "Food", files)), "validation_error");
		assertError(client.post("/api/transactions",
				body("EXPENSE", "1", "31/03/2026", "x", "Food", files)), "validation_error");
		assertError(client.post("/api/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"1\",\"date\":\""
				+ TODAY + "\",\"description\":\"x\",\"fileIds\":[\"" + file + "\"]}"), "validation_error");

		// The biggest amount and a very old date are fine.
		incomeCategory("Salary");
		assertThat(client.post("/api/transactions",
				body("INCOME", "9999999999.99", "1999-01-01", "x".repeat(200), "Salary", files)))
			.hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.amount")
			.isEqualTo("9999999999.99");
	}

	@Test
	void futureDatesAreRefused() {
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		String file = upload(client, "r.pdf");

		// Automatic time zone: "today" anywhere on earth is fine, the day after is not.
		LocalDate latestToday = LocalDate.now(ZoneOffset.ofHours(14));
		assertError(client.post("/api/transactions", body("EXPENSE", "1", latestToday.plusDays(1).toString(), "x",
				"Food", List.of(file))), "future_date");
		assertThat(client.post("/api/transactions", body("EXPENSE", "1", latestToday.toString(), "x", "Food",
				List.of(file)))).hasStatus(HttpStatus.CREATED);

		// With a saved time zone, it is "today" there. Pago Pago is always a day behind Kiritimati.
		client.put("/api/me/preferences", "{\"dateFormat\":\"DD/MM/YYYY\",\"timeZone\":\"Pacific/Pago_Pago\","
				+ "\"theme\":\"SYSTEM\",\"font\":\"GEIST\",\"textSize\":\"NORMAL\"}");
		String file2 = upload(client, "r2.pdf");
		assertError(client.post("/api/transactions", body("EXPENSE", "1",
				LocalDate.now(ZoneId.of("Pacific/Kiritimati")).toString(), "x", "Food", List.of(file2))),
				"future_date");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM transactions WHERE created_by = ?", Integer.class,
				emp.getId())).isEqualTo(1);
	}

	@Test
	void archivedCategoryCanStayButNotBeAdded() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		String file = upload(client, "r.pdf");
		client.post("/api/transactions",
				body("EXPENSE", "5", TODAY, "x", "Bills", List.of(file)));
		String id = onlyTransactionId();
		this.jdbc.update("UPDATE categories SET archived = true WHERE name = 'Bills'");

		// Kept as they were: fine.
		assertThat(client.put("/api/transactions/" + id,
				body("EXPENSE", "6", TODAY, "x", "Bills", List.of(file))))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.category.archived")
			.isEqualTo(true);

		// New uses of archived ones: refused.
		String file2 = upload(client, "r2.pdf");
		assertError(client.post("/api/transactions",
				body("EXPENSE", "5", TODAY, "x", "Bills", List.of(file2))), "validation_error");
		// Moving away from the archived category is fine...
		assertThat(client.put("/api/transactions/" + id,
				body("EXPENSE", "6", TODAY, "x", "Food", List.of(file))))
			.hasStatus(HttpStatus.OK);
		// ...but going back to it is not.
		assertError(client.put("/api/transactions/" + id,
				body("EXPENSE", "6", TODAY, "x", "Bills", List.of(file))), "validation_error");
	}

	@Test
	void historyKeepsOnlyRealChangesWithNames() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		String first = upload(client, "first.pdf");
		client.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Lunch", "Food", List.of(first)));
		String id = onlyTransactionId();

		// Same values (amount written differently): no change, no history.
		assertThat(client.put("/api/transactions/" + id,
				body("EXPENSE", "10.00", TODAY, " Lunch ", "Food", List.of(first))))
			.hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.updatedBy")
			.isNull();
		assertThat(historyCount(id)).isEqualTo(1);

		String second = upload(client, "second.jpg", TestFiles.JPEG, "image/jpeg");
		client.put("/api/transactions/" + id,
				body("EXPENSE", "10", TODAY, "Lunch", "Travel", List.of(second)));
		MvcTestResult history = client.get("/api/transactions/" + id + "/history");
		assertThat(history).bodyJson().extractingPath("$.length()").isEqualTo(2);
		assertThat(history).bodyJson().extractingPath("$[0].action").isEqualTo("UPDATED");
		assertThat(history).bodyJson().extractingPath("$[0].changes[*].field").asArray()
			.containsExactly("category", "files");
		assertThat(history).bodyJson().extractingPath("$[0].changes[0].from").isEqualTo("Food");
		assertThat(history).bodyJson().extractingPath("$[0].changes[0].to").isEqualTo("Travel");
		assertThat(history).bodyJson().extractingPath("$[0].changes[1].from").isEqualTo("first.pdf");
		assertThat(history).bodyJson().extractingPath("$[0].changes[1].to").isEqualTo("second.jpg");

		// The removed file is not on the transaction any more.
		assertThat(this.jdbc.queryForObject("SELECT transaction_id FROM files WHERE id = ?::uuid", UUID.class, first))
			.isNull();
	}

	@Test
	void oldHistoryWithTagsStaysReadable() {
		User emp = activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		client.post("/api/transactions",
				body("EXPENSE", "10", TODAY, "Lunch", "Food", List.of(upload(client, "r.pdf"))));
		String id = onlyTransactionId();
		// A row written before tags were removed.
		this.jdbc.update("INSERT INTO transaction_history (id, transaction_id, user_id, action, changes, created_at) "
				+ "VALUES (?, ?::uuid, ?, 'UPDATED', CAST(? AS jsonb), now() + interval '1 minute')", UUID.randomUUID(),
				id, emp.getId(), "[{\"field\":\"tags\",\"from\":null,\"to\":\"Client X\"}]");
		MvcTestResult history = client.get("/api/transactions/" + id + "/history");
		assertThat(history).hasStatus(HttpStatus.OK);
		assertThat(history).bodyJson().extractingPath("$[0].changes[0].field").isEqualTo("tags");
		assertThat(history).bodyJson().extractingPath("$[0].changes[0].to").isEqualTo("Client X");
	}

	@Test
	void trashRights() {
		activeUser("emp@example.com", Role.MEMBER);
		activeUser("other@example.com", Role.MEMBER);
		activeUser("admin@example.com", Role.OWNER);
		Client author = loggedIn("emp@example.com");
		String a = createdId(author, "A");
		String b = createdId(author, "B");

		Client other = loggedIn("other@example.com");
		assertThat(other.get("/api/transactions/" + a)).bodyJson().extractingPath("$.canDelete").isEqualTo(false);
		assertError(other.delete("/api/transactions/" + a), HttpStatus.FORBIDDEN, "forbidden");

		assertThat(author.delete("/api/transactions/" + a)).hasStatus(HttpStatus.NO_CONTENT);
		Client admin = loggedIn("admin@example.com");
		assertThat(admin.get("/api/transactions/" + b)).bodyJson().extractingPath("$.canDelete").isEqualTo(true);
		assertThat(admin.delete("/api/transactions/" + b)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(author.get("/api/transactions")).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
		assertError(author.get("/api/transactions/" + a), HttpStatus.NOT_FOUND, "transaction_not_found");
		assertError(author.delete("/api/transactions/" + a), HttpStatus.NOT_FOUND, "transaction_not_found");
		assertError(author.get("/api/transactions/" + a + "/history"), HttpStatus.NOT_FOUND, "transaction_not_found");
		assertThat(admin.get("/api/transactions/" + a + "/history")).bodyJson()
			.extractingPath("$[0].action")
			.isEqualTo("DELETED");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(2);
	}

	@Test
	void onlyManagersSeeTrashAndRestore() throws Exception {
		User emp = activeUser("emp@example.com", Role.MEMBER);
		setName(emp, "Ada", null);
		activeUser("owner@example.com", Role.OWNER);
		Client author = loggedIn("emp@example.com");
		String id = createdId(author, "Oops");
		author.delete("/api/transactions/" + id);

		assertError(author.get("/api/admin/transactions/trash"), HttpStatus.FORBIDDEN, "forbidden");
		assertError(author.post("/api/admin/transactions/" + id + "/restore", ""), HttpStatus.FORBIDDEN, "forbidden");

		Client owner = loggedIn("owner@example.com");
		MvcTestResult trash = owner.get("/api/admin/transactions/trash");
		assertThat(trash).bodyJson().extractingPath("$[0].id").isEqualTo(id);
		assertThat(trash).bodyJson().extractingPath("$[0].deletedAt").isNotNull();
		assertThat(trash).bodyJson().extractingPath("$[0].deletedBy.name").isEqualTo("Ada");
		assertThat(trash).bodyJson().extractingPath("$[0].description").isEqualTo("Oops");

		assertThat(owner.post("/api/admin/transactions/" + id + "/restore", "")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.id")
			.isEqualTo(id);
		assertThat(author.get("/api/transactions/" + id)).hasStatus(HttpStatus.OK);
		assertThat(owner.get("/api/admin/transactions/trash")).bodyJson().extractingPath("$.length()").isEqualTo(0);
		assertThat(author.get("/api/transactions/" + id + "/history")).bodyJson()
			.extractingPath("$[*].action")
			.asArray()
			.containsExactly("RESTORED", "DELETED", "CREATED");
		assertError(owner.post("/api/admin/transactions/" + UUID.randomUUID() + "/restore", ""), HttpStatus.NOT_FOUND,
				"transaction_not_found");
	}

	@Test
	void filesOfTrashedTransactionsAreForManagersOnly() {
		activeUser("emp@example.com", Role.MEMBER);
		activeUser("other@example.com", Role.MEMBER);
		activeUser("admin@example.com", Role.OWNER);
		Client author = loggedIn("emp@example.com");
		String file = upload(author, "proof.pdf");
		author.post("/api/transactions", body("EXPENSE", "1", TODAY, "x", "Food", List.of(file)));
		String id = onlyTransactionId();

		Client other = loggedIn("other@example.com");
		Client admin = loggedIn("admin@example.com");
		assertThat(other.get("/api/files/" + file)).hasStatus(HttpStatus.OK);

		author.delete("/api/transactions/" + id);
		assertError(other.get("/api/files/" + file), HttpStatus.NOT_FOUND, "file_not_found");
		assertError(author.get("/api/files/" + file), HttpStatus.NOT_FOUND, "file_not_found");
		assertThat(admin.get("/api/files/" + file)).hasStatus(HttpStatus.OK);
		assertThat(client().get("/api/files/" + file)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void monthListAndTotals() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		add(client, "INCOME", "1000.00", "2026-03-05");
		add(client, "EXPENSE", "250.50", "2026-03-20");
		add(client, "EXPENSE", "99.99", "2026-03-20");
		add(client, "INCOME", "5", "2026-04-01");
		add(client, "EXPENSE", "7", "2026-02-28");
		String trashed = add(client, "INCOME", "100000", "2026-03-10");
		client.delete("/api/transactions/" + trashed);

		MvcTestResult march = client.get("/api/transactions?month=2026-03");
		assertThat(march).hasStatus(HttpStatus.OK);
		assertThat(march).bodyJson().extractingPath("$.items[*].date").asArray()
			.containsExactly("2026-03-20", "2026-03-20", "2026-03-05");
		// Same day: last added first.
		assertThat(march).bodyJson().extractingPath("$.items[0].amount").isEqualTo("99.99");
		assertThat(march).bodyJson().extractingPath("$.totals.in").isEqualTo("1000.00");
		assertThat(march).bodyJson().extractingPath("$.totals.out").isEqualTo("350.49");
		assertThat(march).bodyJson().extractingPath("$.totals.net").isEqualTo("649.51");

		MvcTestResult empty = client.get("/api/transactions?month=2025-01");
		assertThat(empty).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
		assertThat(empty).bodyJson().extractingPath("$.totals.net").isEqualTo("0.00");

		assertError(client.get("/api/transactions?month=2026-13"), "validation_error");
		assertError(client.get("/api/transactions?month=March"), "validation_error");
	}

	@Test
	void defaultMonthIsThisMonthInMyTimeZone() {
		activeUser("emp@example.com", Role.MEMBER);
		Client client = loggedIn("emp@example.com");
		client.put("/api/me/preferences", "{\"dateFormat\":\"DD/MM/YYYY\",\"timeZone\":\"Pacific/Pago_Pago\","
				+ "\"theme\":\"SYSTEM\",\"font\":\"GEIST\",\"textSize\":\"NORMAL\"}");
		YearMonth here = YearMonth.now(ZoneId.of("Pacific/Pago_Pago"));
		add(client, "EXPENSE", "1", here.atDay(1).toString());
		add(client, "EXPENSE", "2", here.minusMonths(1).atEndOfMonth().toString());

		assertThat(client.get("/api/transactions")).bodyJson()
			.extractingPath("$.items[*].amount")
			.asArray()
			.containsExactly("1.00");
	}

	@Test
	void needsLoginAndCsrf() {
		assertThat(client().get("/api/transactions")).hasStatus(HttpStatus.UNAUTHORIZED);
		activeUser("emp@example.com", Role.MEMBER);
		MvcTestResult noCsrf = loggedIn("emp@example.com").postWithoutCsrf("/api/transactions", "{}");
		assertThat(noCsrf).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(noCsrf).bodyJson().extractingPath("$.error").isEqualTo("csrf_invalid");
	}

	// ---- helpers ----

	private String add(Client client, String type, String amount, String date) {
		String file = upload(client, "proof.pdf");
		incomeCategory("Salary");
		MvcTestResult result = client.post("/api/transactions",
				body(type, amount, date, "x", "INCOME".equals(type) ? "Salary" : "Food", List.of(file)));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return this.jdbc.queryForObject("SELECT transaction_id::text FROM files WHERE id = ?::uuid", String.class, file);
	}

	private String createdId(Client client, String description) {
		String file = upload(client, "proof.pdf");
		assertThat(client.post("/api/transactions", confirmed(
				body("EXPENSE", "1", TODAY, description, "Food", List.of(file)))))
			.hasStatus(HttpStatus.CREATED);
		return this.jdbc.queryForObject("SELECT transaction_id::text FROM files WHERE id = ?::uuid", String.class, file);
	}

	private static String upload(Client client, String name) {
		return upload(client, name, TestFiles.PDF, "application/pdf");
	}

	private static String upload(Client client, String name, byte[] content, String type) {
		return FilesIntegrationTests.uploadedId(client.upload(name, type, content));
	}

	private String categoryId(String name) {
		return this.jdbc.queryForObject("SELECT id::text FROM categories WHERE name = ?", String.class, name);
	}

	private String onlyTransactionId() {
		return this.jdbc.queryForObject("SELECT id::text FROM transactions", String.class);
	}

	private int historyCount(String id) {
		return this.jdbc.queryForObject("SELECT count(*) FROM transaction_history WHERE transaction_id = ?::uuid",
				Integer.class, id);
	}

	private void setName(User user, String first, String last) {
		this.jdbc.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?", first, last, user.getId());
	}

	private String body(String type, String amount, String date, String description,
			String category, List<String> fileIds) {
		return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"date\":\""
				+ date + "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId(category)
				+ "\",\"fileIds\":" + ids(fileIds) + "}";
	}

	/** The same body with "confirmDuplicate": true. */
	static String confirmed(String body) {
		return body.substring(0, body.length() - 1) + ",\"confirmDuplicate\":true}";
	}

	private static String ids(List<String> ids) {
		return (ids == null) ? "null"
				: ids.stream().map((id) -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	private static void assertError(MvcTestResult result, String code) {
		assertError(result, HttpStatus.BAD_REQUEST, code);
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

}
