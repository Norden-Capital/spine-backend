package com.nordencapital.spine.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push with the JDK only (no extra library):
 * <ul>
 * <li>message encryption, RFC 8291 + RFC 8188 ({@code aes128gcm}, one record);</li>
 * <li>VAPID, RFC 8292: an ES256 JWT signed with the server key.</li>
 * </ul>
 * Keys are P-256, written as base64url without padding: a public key is the 65-byte
 * uncompressed point (0x04 || x || y), a private key the 32-byte number d.
 */
public final class WebPushCrypto {

	/** Record size written in the header (one record holds the whole message). */
	static final int RECORD_SIZE = 4096;

	private static final ECParameterSpec P256 = p256();

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

	private WebPushCrypto() {
	}

	// ---- keys ----

	/** @throws IllegalArgumentException not a valid P-256 public key */
	public static ECPublicKey publicKey(byte[] uncompressed) {
		if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
			throw new IllegalArgumentException("not a 65-byte uncompressed P-256 point");
		}
		BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
		BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
		try {
			return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalArgumentException("not a point on P-256", ex);
		}
	}

	/** @throws IllegalArgumentException not a valid P-256 private key */
	public static ECPrivateKey privateKey(byte[] d) {
		if (d.length != 32) {
			throw new IllegalArgumentException("a P-256 private key has 32 bytes");
		}
		try {
			return (ECPrivateKey) KeyFactory.getInstance("EC")
				.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalArgumentException("not a P-256 private key", ex);
		}
	}

	/** The 65-byte uncompressed point. */
	public static byte[] encode(ECPublicKey key) {
		byte[] out = new byte[65];
		out[0] = 0x04;
		copy32(key.getW().getAffineX(), out, 1);
		copy32(key.getW().getAffineY(), out, 33);
		return out;
	}

	/** True if the private key belongs to the public key (signs, then verifies). */
	public static boolean isPair(ECPrivateKey privateKey, ECPublicKey publicKey) {
		try {
			byte[] data = "spine-vapid-check".getBytes(StandardCharsets.US_ASCII);
			Signature signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(privateKey);
			signer.update(data);
			byte[] signature = signer.sign();
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(publicKey);
			verifier.update(data);
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException ex) {
			return false;
		}
	}

	public static KeyPair newKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static byte[] base64Url(String text) {
		return Base64.getUrlDecoder().decode(text.trim().replace('+', '-').replace('/', '_').replace("=", ""));
	}

	public static String base64Url(byte[] bytes) {
		return B64URL.encodeToString(bytes);
	}

	// ---- encryption (RFC 8291) ----

	/**
	 * Encrypts a message for one browser.
	 * @param uaPublic the browser's p256dh key (65 bytes)
	 * @param authSecret the browser's auth secret (16 bytes)
	 * @return the request body: salt, record size, our public key, then the cipher text
	 */
	public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		return encrypt(plaintext, uaPublic, authSecret, newKeyPair(), salt);
	}

	/** Same, with a fixed server key pair and salt (for the RFC test vector). */
	static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
		if (authSecret.length != 16) {
			throw new IllegalArgumentException("the auth secret has 16 bytes");
		}
		if (plaintext.length > RECORD_SIZE - 17 - 86) {
			throw new IllegalArgumentException("message too long for one record");
		}
		try {
			byte[] asPublic = encode((ECPublicKey) asKeys.getPublic());
			KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
			agreement.init(asKeys.getPrivate());
			agreement.doPhase(publicKey(uaPublic), true);
			byte[] ecdhSecret = agreement.generateSecret();

			// IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" 0x00 ua_public as_public, 32)
			byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
			byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
			// CEK and NONCE from the salt (RFC 8188).
			byte[] prk = hmac(salt, ikm);
			byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
			byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

			// One record, the last one: the message, then the 0x02 delimiter.
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
			byte[] cipherText = cipher.doFinal(concat(plaintext, new byte[] { 0x02 }));

			return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + cipherText.length)
				.put(salt)
				.putInt(RECORD_SIZE)
				.put((byte) asPublic.length)
				.put(asPublic)
				.put(cipherText)
				.array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Web Push encryption failed", ex);
		}
	}

	// ---- VAPID (RFC 8292) ----

	/**
	 * The Authorization header value: {@code vapid t=<jwt>, k=<public key>}.
	 * @param audience the push service origin, like {@code https://fcm.googleapis.com}
	 * @param expiresAt seconds since 1970 (at most 24 hours ahead)
	 */
	public static String vapidAuthorization(String audience, long expiresAt, String subject, ECPrivateKey privateKey,
			byte[] publicKey) {
		String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
		String claims = base64Url(("{\"aud\":\"" + jsonText(audience) + "\",\"exp\":" + expiresAt + ",\"sub\":\""
				+ jsonText(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + claims;
		try {
			// JWT wants the raw 64-byte r || s, not DER.
			Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
			signer.initSign(privateKey);
			signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return "vapid t=" + signingInput + "." + base64Url(signer.sign()) + ", k=" + base64Url(publicKey);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("VAPID signing failed", ex);
		}
	}

	// ---- helpers ----

	static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
		return expand(hmac(salt, ikm), info, length);
	}

	/** HKDF-Expand for at most 32 bytes (one block). */
	static byte[] expand(byte[] prk, byte[] info, int length) {
		return Arrays.copyOf(hmac(prk, concat(info, new byte[] { 0x01 })), length);
	}

	static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		ByteBuffer buffer = ByteBuffer.allocate(length);
		for (byte[] part : parts) {
			buffer.put(part);
		}
		return buffer.array();
	}

	private static void copy32(BigInteger value, byte[] out, int offset) {
		byte[] bytes = value.toByteArray();
		int start = Math.max(0, bytes.length - 32);
		int length = bytes.length - start;
		System.arraycopy(bytes, start, out, offset + 32 - length, length);
	}

	private static String jsonText(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static ECParameterSpec p256() {
		try {
			AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			return parameters.getParameterSpec(ECParameterSpec.class);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
