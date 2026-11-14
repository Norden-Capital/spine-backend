package com.nordencapital.spine.push;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * The checked VAPID keys of this server.
 * @param publicKeyText as given (base64url), for the browser's applicationServerKey
 */
record VapidKeys(String publicKeyText, byte[] publicKey, ECPrivateKey privateKey, String subject) {

	/**
	 * @return null when push is not configured
	 * @throws IllegalStateException only some keys set, bad keys, keys that do not match, or a
	 * subject that is not mailto: / https:
	 */
	static VapidKeys from(PushProperties properties) {
		if (!properties.isSet()) {
			return null;
		}
		String publicText = require(properties.vapidPublicKey(), "SPINE_VAPID_PUBLIC_KEY");
		String privateText = require(properties.vapidPrivateKey(), "SPINE_VAPID_PRIVATE_KEY");
		String subject = require(properties.vapidSubject(), "SPINE_VAPID_SUBJECT").strip();
		if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
			throw new IllegalStateException("SPINE_VAPID_SUBJECT must start with mailto: or https://");
		}
		ECPublicKey publicKey;
		ECPrivateKey privateKey;
		try {
			publicKey = WebPushCrypto.publicKey(WebPushCrypto.base64Url(publicText));
			privateKey = WebPushCrypto.privateKey(WebPushCrypto.base64Url(privateText));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("The VAPID keys are not valid P-256 keys (base64url): " + ex.getMessage());
		}
		if (!WebPushCrypto.isPair(privateKey, publicKey)) {
			throw new IllegalStateException("SPINE_VAPID_PRIVATE_KEY does not belong to SPINE_VAPID_PUBLIC_KEY");
		}
		byte[] encoded = WebPushCrypto.encode(publicKey);
		return new VapidKeys(WebPushCrypto.base64Url(encoded), encoded, privateKey, subject);
	}

	private static String require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is empty: set all three VAPID variables, or none (push off)");
		}
		return value.strip();
	}

	@Override
	public String toString() {
		return "VapidKeys[" + this.publicKeyText + "]";
	}

}
