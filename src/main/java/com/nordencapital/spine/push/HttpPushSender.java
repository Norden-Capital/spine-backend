package com.nordencapital.spine.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/**
 * Sends with the JDK HTTP client: the message encrypted (aes128gcm) and a VAPID token for
 * the push service's origin, valid 12 hours. Messages live up to 24 hours at the push
 * service when the phone is offline.
 */
class HttpPushSender implements PushSender {

	static final int TTL_SECONDS = 24 * 60 * 60;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private final VapidKeys keys;

	private final Clock clock;

	HttpPushSender(VapidKeys keys, Clock clock) {
		this.keys = keys;
		this.clock = clock;
	}

	@Override
	public int send(Target target, byte[] payload) {
		URI endpoint = URI.create(target.endpoint());
		String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
		long expiresAt = this.clock.instant().plus(Duration.ofHours(12)).getEpochSecond();
		byte[] body = WebPushCrypto.encrypt(payload, WebPushCrypto.base64Url(target.p256dh()),
				WebPushCrypto.base64Url(target.auth()));
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(15))
			.header("TTL", String.valueOf(TTL_SECONDS))
			.header("Urgency", "normal")
			.header("Content-Type", "application/octet-stream")
			.header("Content-Encoding", "aes128gcm")
			.header("Authorization", WebPushCrypto.vapidAuthorization(audience, expiresAt, this.keys.subject(),
					this.keys.privateKey(), this.keys.publicKey()))
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build();
		try {
			return this.http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
		catch (IOException ex) {
			throw new IllegalStateException("push service not reachable: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", ex);
		}
	}

}
