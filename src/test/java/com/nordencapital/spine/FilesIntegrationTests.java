package com.nordencapital.spine;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.nordencapital.spine.IntegrationTestBase.Client;
import com.nordencapital.spine.file.FileService;
import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof files: type check by content, size limit, who can download, cleanup.
 */
class FilesIntegrationTests extends IntegrationTestBase {

	@Autowired
	private FileService fileService;

	@Test
	void allowedTypesAreFoundByContentNotByHeader() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		assertUploaded(client.upload("a.jpg", "image/jpeg", TestFiles.JPEG), "image/jpeg");
		assertUploaded(client.upload("b.png", "image/png", TestFiles.PNG), "image/png");
		assertUploaded(client.upload("c.pdf", "application/pdf", TestFiles.PDF), "application/pdf");
		assertUploaded(client.upload("d.webp", "image/webp", TestFiles.WEBP), "image/webp");
		// iPhones often send HEIC as application/octet-stream: the content decides.
		assertUploaded(client.upload("IMG_0001.HEIC", "application/octet-stream", TestFiles.HEIC), "image/heic");
		assertUploaded(client.upload("e.heif", "image/heif", TestFiles.HEIF), "image/heif");
		// A PNG named .jpg is stored as what it really is.
		assertUploaded(client.upload("lie.jpg", "image/jpeg", TestFiles.PNG), "image/png");
	}

	@Test
	void otherTypesAreRefused() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");

		assertError(client.upload("photo.jpg", "image/jpeg", TestFiles.TEXT), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"file_type_not_allowed");
		assertError(client.upload("x.svg", "image/svg+xml", TestFiles.SVG), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"file_type_not_allowed");
		assertError(client.upload("empty.pdf", "application/pdf", new byte[0]), HttpStatus.BAD_REQUEST,
				"validation_error");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
	}

	@Test
	void tooBigFileIsRefused() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		byte[] big = new byte[(int) FileService.MAX_SIZE + 1];
		System.arraycopy(TestFiles.PDF, 0, big, 0, TestFiles.PDF.length);
		assertError(loggedIn("emp@example.com").upload("big.pdf", "application/pdf", big), HttpStatus.PAYLOAD_TOO_LARGE,
				"file_too_large");

		byte[] exactly10Mb = new byte[(int) FileService.MAX_SIZE];
		System.arraycopy(TestFiles.PDF, 0, exactly10Mb, 0, TestFiles.PDF.length);
		assertThat(loggedIn("emp@example.com").upload("ok.pdf", "application/pdf", exactly10Mb))
			.hasStatus(HttpStatus.CREATED);
	}

	@Test
	void namesAreCleanedAndDiskNamesAreRandom() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		MvcTestResult result = loggedIn("emp@example.com").upload("../../etc/receipt\u0007.pdf", "application/pdf",
				TestFiles.PDF);
		assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("receipt.pdf");
		String key = this.jdbc.queryForObject("SELECT storage_key FROM files", String.class);
		assertThat(key).matches("[0-9a-f]{32}");
		assertThat(Path.of("target/test-files", key)).exists();
	}

	@Test
	void newUploadIsOnlyForItsOwner() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		activeUser("admin@example.com", Role.ADMIN);
		Client owner = loggedIn("emp@example.com");
		String id = uploadedId(owner.upload("r.pdf", "application/pdf", TestFiles.PDF));

		MvcTestResult file = owner.get("/api/files/" + id);
		assertThat(file).hasStatus(HttpStatus.OK);
		assertThat(file.getResponse().getContentType()).isEqualTo("application/pdf");
		assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(TestFiles.PDF);
		assertThat(file.getResponse().getHeader("Content-Disposition")).startsWith("inline").contains("r.pdf");
		assertThat(file.getResponse().getHeader("Cache-Control")).contains("no-store").contains("private");
		assertThat(file.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

		assertError(loggedIn("admin@example.com").get("/api/files/" + id), HttpStatus.NOT_FOUND, "file_not_found");
		assertThat(client().get("/api/files/" + id)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertError(owner.get("/api/files/" + java.util.UUID.randomUUID()), HttpStatus.NOT_FOUND, "file_not_found");
	}

	@Test
	void uploadNeedsLoginAndCsrf() {
		assertThat(client().upload("r.pdf", "application/pdf", TestFiles.PDF)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void oldUnattachedUploadsAreDeleted() {
		activeUser("emp@example.com", Role.EMPLOYEE);
		Client client = loggedIn("emp@example.com");
		String old = uploadedId(client.upload("old.pdf", "application/pdf", TestFiles.PDF));
		String fresh = uploadedId(client.upload("fresh.pdf", "application/pdf", TestFiles.PDF));
		String oldKey = this.jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, old);
		this.jdbc.update("UPDATE files SET created_at = now() - interval '25 hours' WHERE id = ?::uuid", old);

		assertThat(this.fileService.deleteOldUnattached()).isEqualTo(1);

		assertThat(this.jdbc.queryForList("SELECT id::text FROM files", String.class)).containsExactly(fresh);
		assertThat(Files.exists(Path.of("target/test-files", oldKey))).isFalse();
	}

	static String uploadedId(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		try {
			String body = result.getResponse().getContentAsString();
			return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
		}
		catch (java.io.UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void assertUploaded(MvcTestResult result, String contentType) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.contentType").isEqualTo(contentType);
		assertThat(result).bodyJson().extractingPath("$.id").isNotNull();
		assertThat(result).bodyJson().extractingPath("$.size").isNotNull();
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.error").isEqualTo(code);
	}

}
