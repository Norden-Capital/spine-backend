package com.nordencapital.spine.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Web Push encryption against the example of RFC 8291 (Appendix A), a round trip as a
 * browser would decrypt it, and the VAPID token.
 */
class WebPushCryptoTests {

	// RFC 8291, Appendix A.
	private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";

	private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";

	private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";

	private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";

	private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";

	private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";

	private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";

	private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

	@Test
	void matchesTheRfc8291Example() {
		ECPublicKey asPublic = WebPushCrypto.publicKey(WebPushCrypto.base64Url(AS_PUBLIC));
		ECPrivateKey asPrivate = WebPushCrypto.privateKey(WebPushCrypto.base64Url(AS_PRIVATE));
		assertThat(WebPushCrypto.isPair(asPrivate, asPublic)).isTrue();
		assertThat(WebPushCrypto.base64Url(WebPushCrypto.encode(asPublic))).isEqualTo(AS_PUBLIC);

		byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
				WebPushCrypto.base64Url(UA_PUBLIC), WebPushCrypto.base64Url(AUTH_SECRET),
				new KeyPair(asPublic, asPrivate), WebPushCrypto.base64Url(SALT));
		assertThat(WebPushCrypto.base64Url(body)).isEqualTo(BODY);
	}

	@Test
	void aBrowserCanDecryptIt() throws Exception {
		KeyPair browser = WebPushCrypto.newKeyPair();
		byte[] uaPublic = WebPushCrypto.encode((ECPublicKey) browser.getPublic());
		byte[] auth = new byte[16];
		Arrays.fill(auth, (byte) 7);
		String message = "{\"title\":\"Twilio is empty\",\"body\":\"é ✓\"}";

		byte[] body = WebPushCrypto.encrypt(message.getBytes(StandardCharsets.UTF_8), uaPublic, auth);
		assertThat(decrypt(body, browser, uaPublic, auth)).isEqualTo(message);
		// A new salt and key every time.
		assertThat(WebPushCrypto.encrypt(message.getBytes(StandardCharsets.UTF_8), uaPublic, auth)).isNotEqualTo(body);
	}

	@Test
	void vapidTokenIsSignedWithTheServerKey() throws Exception {
		KeyPair server = WebPushCrypto.newKeyPair();
		byte[] publicKey = WebPushCrypto.encode((ECPublicKey) server.getPublic());
		String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com", 1_800_000_000L,
				"mailto:ops@example.com", (ECPrivateKey) server.getPrivate(), publicKey);

		assertThat(header).startsWith("vapid t=").endsWith(", k=" + WebPushCrypto.base64Url(publicKey));
		String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
		String[] parts = jwt.split("\\.");
		assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
			.isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
		assertThat(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
			.isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800000000,\"sub\":\"mailto:ops@example.com\"}");
		byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
		assertThat(signature).hasSize(64);
		Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
		verifier.initVerify(server.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(signature)).isTrue();
	}

	@Test
	void badKeysAreRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> WebPushCrypto.publicKey(new byte[65]));
		assertThatIllegalArgumentException().isThrownBy(() -> WebPushCrypto.publicKey(new byte[33]));
		assertThatIllegalArgumentException().isThrownBy(() -> WebPushCrypto.privateKey(new byte[31]));
		KeyPair one = WebPushCrypto.newKeyPair();
		KeyPair two = WebPushCrypto.newKeyPair();
		assertThat(WebPushCrypto.isPair((ECPrivateKey) one.getPrivate(), (ECPublicKey) two.getPublic())).isFalse();
	}

	/** What the browser does (RFC 8291, receiving side). */
	private static String decrypt(byte[] body, KeyPair browser, byte[] uaPublic, byte[] auth) throws Exception {
		ByteBuffer buffer = ByteBuffer.wrap(body);
		byte[] salt = new byte[16];
		buffer.get(salt);
		assertThat(buffer.getInt()).isEqualTo(4096);
		byte[] asPublic = new byte[buffer.get()];
		buffer.get(asPublic);
		byte[] cipherText = new byte[buffer.remaining()];
		buffer.get(cipherText);

		KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(browser.getPrivate());
		agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
		byte[] keyInfo = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
		byte[] ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(), keyInfo, 32);
		byte[] prk = WebPushCrypto.hmac(salt, ikm);
		byte[] cek = WebPushCrypto.expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
		byte[] nonce = WebPushCrypto.expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		byte[] plain = cipher.doFinal(cipherText);
		assertThat(plain[plain.length - 1]).isEqualTo((byte) 0x02);
		return new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8);
	}

}
