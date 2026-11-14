package com.nordencapital.spine.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VAPID keys for Web Push ({@code app.push.*}, from SPINE_VAPID_PUBLIC_KEY,
 * SPINE_VAPID_PRIVATE_KEY, SPINE_VAPID_SUBJECT). All three empty = push is off: the push
 * endpoints answer 503 {@code push_disabled} and the rest of the app works as before.
 * @param vapidPublicKey base64url, the 65-byte uncompressed P-256 point
 * @param vapidPrivateKey base64url, the 32-byte P-256 private number
 * @param vapidSubject a contact for the push services, like {@code mailto:ops@example.com}
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(String vapidPublicKey, String vapidPrivateKey, String vapidSubject) {

	public boolean isSet() {
		return notBlank(this.vapidPublicKey) || notBlank(this.vapidPrivateKey) || notBlank(this.vapidSubject);
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

}
