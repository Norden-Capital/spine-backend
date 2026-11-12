package com.nordencapital.spine;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.nordencapital.spine.user.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upload size limit through a real HTTP server (MockMvc skips the multipart limits): a
 * too big file must get a clean 413 JSON answer, not a broken connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.bootstrap.admin-email=bootstrap@example.com", "app.files.dir=target/test-files" })
class UploadLimitHttpTests extends IntegrationTestBase {

	@LocalServerPort
	private int port;

	@Test
	void tooBigUploadGets413Json() throws Exception {
		activeUser("emp@example.com", Role.EMPLOYEE);
		CookieManager cookies = new CookieManager();
		HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

		http.send(HttpRequest.newBuilder(url("/api/auth/csrf")).build(), HttpResponse.BodyHandlers.discarding());
		HttpResponse<String> login = http.send(HttpRequest.newBuilder(url("/api/auth/login"))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", csrf(cookies))
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"email\":\"emp@example.com\",\"password\":\"" + PASSWORD + "\"}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(200);

		HttpResponse<String> tooBig = upload(http, cookies, 10 * 1024 * 1024 + 500_000);
		assertThat(tooBig.statusCode()).isEqualTo(413);
		assertThat(tooBig.body()).contains("\"error\":\"file_too_large\"");

		HttpResponse<String> fine = upload(http, cookies, 9 * 1024 * 1024);
		assertThat(fine.statusCode()).isEqualTo(201);
	}

	private HttpResponse<String> upload(HttpClient http, CookieManager cookies, int size) throws Exception {
		byte[] content = new byte[size];
		System.arraycopy(TestFiles.PDF, 0, content, 0, TestFiles.PDF.length);
		String boundary = "----spine-test-boundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.pdf\"\r\n"
				+ "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		body.write(content);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return http.send(HttpRequest.newBuilder(url("/api/files"))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header("X-XSRF-TOKEN", csrf(cookies))
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String csrf(CookieManager cookies) {
		return cookies.getCookieStore()
			.getCookies()
			.stream()
			.filter((cookie) -> cookie.getName().equals("XSRF-TOKEN"))
			.map(HttpCookie::getValue)
			.reduce((first, last) -> last)
			.orElseThrow();
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

}
