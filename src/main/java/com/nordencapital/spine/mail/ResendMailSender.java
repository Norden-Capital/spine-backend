package com.nordencapital.spine.mail;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Sends emails with the Resend HTTP API (https://resend.com/docs/api-reference/emails/send-email).
 */
public class ResendMailSender implements MailSender {

	static final String DEFAULT_BASE_URL = "https://api.resend.com";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final RestClient client;

	private final String from;

	public ResendMailSender(String apiKey, String from) {
		this(DEFAULT_BASE_URL, apiKey, from);
	}

	ResendMailSender(String baseUrl, String apiKey, String from) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(TIMEOUT);
		this.client = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
			.build();
		this.from = from;
	}

	@Override
	public void send(MailMessage message) {
		Map<String, Object> body = Map.of("from", this.from, "to", List.of(message.to()), "subject",
				message.subject(), "html", message.html(), "text", message.text());
		this.client.post()
			.uri("/emails")
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.toBodilessEntity();
	}

}
