package com.nordencapital.spine.push;

/**
 * Sends one encrypted message to one push service endpoint.
 */
public interface PushSender {

	/**
	 * @param payload the plain message (UTF-8 JSON); the sender encrypts it
	 * @return the HTTP status of the push service (201 = accepted, 404/410 = gone)
	 * @throws RuntimeException on a network error
	 */
	int send(Target target, byte[] payload);

	/**
	 * @param p256dh the browser's public key (base64url)
	 * @param auth the browser's auth secret (base64url)
	 */
	record Target(String endpoint, String p256dh, String auth) {
	}

}
