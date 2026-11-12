package com.nordencapital.spine.passkey;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A software passkey for tests: acts like Face ID on a phone. It makes an EC P-256 key,
 * answers creation and request options, and returns the same JSON a browser gives with
 * {@code credential.toJSON()} (binary values in base64url). Attestation is "none", like
 * Apple passkeys.
 */
public final class FakeAuthenticator {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder B64_DECODE = Base64.getUrlDecoder();

	private final KeyPair keys;

	private final byte[] credentialId = new byte[32];

	private final String origin;

	private byte[] userHandle;

	private int counter;

	public FakeAuthenticator(String origin) {
		this.origin = origin;
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			this.keys = generator.generateKeyPair();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		new SecureRandom().nextBytes(this.credentialId);
	}

	public String credentialIdBase64() {
		return B64.encodeToString(this.credentialId);
	}

	/** Like navigator.credentials.create(): returns RegistrationResponseJSON. */
	public String register(String creationOptionsJson) throws Exception {
		JsonNode options = JSON.readTree(creationOptionsJson);
		String rpId = options.get("rp").get("id").asText();
		this.userHandle = B64_DECODE.decode(options.get("user").get("id").asText());
		byte[] clientData = clientData("webauthn.create", options.get("challenge").asText());

		ByteArrayOutputStream authData = new ByteArrayOutputStream();
		authData.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
		authData.write(0x01 | 0x04 | 0x40); // user present, user verified, has credential data
		authData.write(ByteBuffer.allocate(4).putInt(this.counter).array());
		authData.write(new byte[16]); // AAGUID
		authData.write(ByteBuffer.allocate(2).putShort((short) this.credentialId.length).array());
		authData.write(this.credentialId);
		authData.write(coseKey());

		Cbor attestation = new Cbor().map(3)
			.text("fmt")
			.text("none")
			.text("attStmt")
			.map(0)
			.text("authData")
			.bytes(authData.toByteArray());

		return JSON.writeValueAsString(JSON.createObjectNode()
			.put("id", credentialIdBase64())
			.put("rawId", credentialIdBase64())
			.put("type", "public-key")
			.put("authenticatorAttachment", "platform")
			.<com.fasterxml.jackson.databind.node.ObjectNode>set("response", JSON.createObjectNode()
				.put("clientDataJSON", B64.encodeToString(clientData))
				.put("attestationObject", B64.encodeToString(attestation.toBytes()))
				.put("authenticatorData", B64.encodeToString(authData.toByteArray()))
				.put("publicKey", B64.encodeToString(this.keys.getPublic().getEncoded()))
				.put("publicKeyAlgorithm", -7)
				.set("transports", JSON.createArrayNode().add("internal").add("hybrid")))
			.set("clientExtensionResults", JSON.createObjectNode()));
	}

	/** Like navigator.credentials.get(): returns AuthenticationResponseJSON. */
	public String login(String requestOptionsJson) throws Exception {
		return login(requestOptionsJson, false);
	}

	/** Same as {@link #login(String)}, but with a broken signature when {@code badSignature}. */
	public String login(String requestOptionsJson, boolean badSignature) throws Exception {
		JsonNode options = JSON.readTree(requestOptionsJson);
		String rpId = options.get("rpId").asText();
		byte[] clientData = clientData("webauthn.get", options.get("challenge").asText());
		this.counter++;

		ByteArrayOutputStream authData = new ByteArrayOutputStream();
		authData.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
		authData.write(0x01 | 0x04); // user present, user verified
		authData.write(ByteBuffer.allocate(4).putInt(this.counter).array());

		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(this.keys.getPrivate());
		signer.update(authData.toByteArray());
		signer.update(sha256(badSignature ? "something else".getBytes(StandardCharsets.UTF_8) : clientData));

		return JSON.writeValueAsString(JSON.createObjectNode()
			.put("id", credentialIdBase64())
			.put("rawId", credentialIdBase64())
			.put("type", "public-key")
			.put("authenticatorAttachment", "platform")
			.<com.fasterxml.jackson.databind.node.ObjectNode>set("response", JSON.createObjectNode()
				.put("clientDataJSON", B64.encodeToString(clientData))
				.put("authenticatorData", B64.encodeToString(authData.toByteArray()))
				.put("signature", B64.encodeToString(signer.sign()))
				.put("userHandle", B64.encodeToString(this.userHandle)))
			.set("clientExtensionResults", JSON.createObjectNode()));
	}

	private byte[] clientData(String type, String challenge) throws Exception {
		return JSON.writeValueAsBytes(JSON.createObjectNode()
			.put("type", type)
			.put("challenge", challenge)
			.put("origin", this.origin)
			.put("crossOrigin", false));
	}

	/** The public key as a COSE_Key (EC2, P-256, ES256). */
	private byte[] coseKey() {
		ECPublicKey publicKey = (ECPublicKey) this.keys.getPublic();
		return new Cbor().map(5)
			.integer(1)
			.integer(2) // kty: EC2
			.integer(3)
			.integer(-7) // alg: ES256
			.integer(-1)
			.integer(1) // crv: P-256
			.integer(-2)
			.bytes(fixed32(publicKey.getW().getAffineX()))
			.integer(-3)
			.bytes(fixed32(publicKey.getW().getAffineY()))
			.toBytes();
	}

	private static byte[] fixed32(BigInteger value) {
		byte[] raw = value.toByteArray();
		byte[] out = new byte[32];
		int copy = Math.min(raw.length, 32);
		System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
		return out;
	}

	private static byte[] sha256(byte[] data) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	/** Just enough CBOR for WebAuthn: maps, small integers, byte and text strings. */
	private static final class Cbor {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Cbor map(int size) {
			head(5, size);
			return this;
		}

		Cbor integer(long value) {
			if (value >= 0) {
				head(0, value);
			}
			else {
				head(1, -1 - value);
			}
			return this;
		}

		Cbor bytes(byte[] value) {
			head(2, value.length);
			this.out.writeBytes(value);
			return this;
		}

		Cbor text(String value) {
			byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
			head(3, utf8.length);
			this.out.writeBytes(utf8);
			return this;
		}

		byte[] toBytes() {
			return this.out.toByteArray();
		}

		private void head(int major, long value) {
			int type = major << 5;
			if (value < 24) {
				this.out.write(type | (int) value);
			}
			else if (value < 256) {
				this.out.write(type | 24);
				this.out.write((int) value);
			}
			else {
				this.out.write(type | 25);
				this.out.write((int) (value >> 8));
				this.out.write((int) value);
			}
		}

	}

}
