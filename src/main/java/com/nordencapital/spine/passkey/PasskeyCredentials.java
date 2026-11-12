package com.nordencapital.spine.passkey;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.user.UserRepository;

/**
 * Gives the WebAuthn library our stored passkeys.
 * <p>
 * WebAuthn words: the "username" is the user's email, the "user handle" is the user's
 * id (16 bytes of the UUID). The handle is stored in the passkey on the phone, so login
 * works without typing an email.
 */
@Component
class PasskeyCredentials implements CredentialRepository {

	private final PasskeyRepository passkeys;

	private final UserRepository users;

	PasskeyCredentials(PasskeyRepository passkeys, UserRepository users) {
		this.passkeys = passkeys;
		this.users = users;
	}

	@Override
	public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String email) {
		return this.users.findByEmail(email)
			.map((user) -> this.passkeys.findAllByUserIdOrderByCreatedAtAsc(user.getId())
				.stream()
				.map(PasskeyCredentials::descriptor)
				.collect(Collectors.toSet()))
			.orElse(Set.of());
	}

	@Override
	public Optional<ByteArray> getUserHandleForUsername(String email) {
		return this.users.findByEmail(email).map((user) -> handle(user.getId()));
	}

	@Override
	public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
		return userId(userHandle).flatMap(this.users::findById).map((user) -> user.getEmail());
	}

	@Override
	public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
		Optional<UUID> userId = userId(userHandle);
		return this.passkeys.findByCredentialId(credentialId.getBytes())
			.filter((passkey) -> userId.isPresent() && passkey.getUserId().equals(userId.get()))
			.map(PasskeyCredentials::registered);
	}

	@Override
	public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
		return this.passkeys.findByCredentialId(credentialId.getBytes())
			.map(PasskeyCredentials::registered)
			.map(Set::of)
			.orElse(Set.of());
	}

	static ByteArray handle(UUID userId) {
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(userId.getMostSignificantBits());
		buffer.putLong(userId.getLeastSignificantBits());
		return new ByteArray(buffer.array());
	}

	static Optional<UUID> userId(ByteArray userHandle) {
		if (userHandle == null || userHandle.size() != 16) {
			return Optional.empty();
		}
		ByteBuffer buffer = ByteBuffer.wrap(userHandle.getBytes());
		return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
	}

	private static RegisteredCredential registered(Passkey passkey) {
		return RegisteredCredential.builder()
			.credentialId(new ByteArray(passkey.getCredentialId()))
			.userHandle(handle(passkey.getUserId()))
			.publicKeyCose(new ByteArray(passkey.getPublicKey()))
			.signatureCount(passkey.getSignCount())
			.build();
	}

	private static PublicKeyCredentialDescriptor descriptor(Passkey passkey) {
		PublicKeyCredentialDescriptor.PublicKeyCredentialDescriptorBuilder builder = PublicKeyCredentialDescriptor
			.builder()
			.id(new ByteArray(passkey.getCredentialId()));
		if (passkey.getTransports() != null && !passkey.getTransports().isBlank()) {
			builder.transports(Arrays.stream(passkey.getTransports().split(","))
				.map(AuthenticatorTransport::of)
				.collect(Collectors.toSet()));
		}
		return builder.build();
	}

}
