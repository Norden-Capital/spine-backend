package com.nordencapital.spine.passkey;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.auth.AuthLimiters;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.auth.LoginEvents;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserRepository;
import com.nordencapital.spine.user.UserStatus;

/**
 * Passkeys (WebAuthn): add, list, rename, delete my passkeys, and log in with one.
 * <p>
 * Each ceremony has 2 steps. Step 1 makes options with a random challenge and keeps them
 * in the server session for {@value #CEREMONY_MINUTES} minutes. Step 2 checks the
 * browser's answer against them (once only). Passkeys are an extra way in; the password
 * stays for recovery.
 */
@Service
public class PasskeyService {

	static final int CEREMONY_MINUTES = 5;

	static final String DEFAULT_NAME = "Passkey";

	private static final long BROWSER_TIMEOUT_MS = 60_000;

	private static final String REGISTRATION_KEY = "spine.passkey.registration";

	private static final String LOGIN_KEY = "spine.passkey.login";

	private static final Logger logger = LoggerFactory.getLogger(PasskeyService.class);

	/** Jackson 2, the JSON library the WebAuthn library uses. */
	private static final ObjectMapper JSON = new ObjectMapper();

	private final RelyingParty relyingParty;

	private final PasskeyRepository passkeys;

	private final UserRepository users;

	private final Actors actors;

	private final LoginEvents loginEvents;

	private final AuthLimiters limiters;

	private final Clock clock;

	public PasskeyService(RelyingParty relyingParty, PasskeyRepository passkeys, UserRepository users, Actors actors,
			LoginEvents loginEvents, AuthLimiters limiters, Clock clock) {
		this.relyingParty = relyingParty;
		this.passkeys = passkeys;
		this.users = users;
		this.actors = actors;
		this.loginEvents = loginEvents;
		this.limiters = limiters;
		this.clock = clock;
	}

	// ---- my passkeys ----

	/**
	 * Step 1 of adding a passkey.
	 * @return PublicKeyCredentialCreationOptionsJSON (binary values in base64url)
	 */
	@Transactional(readOnly = true)
	public String registrationOptions(AuthUser principal, HttpSession session) {
		User user = this.actors.active(principal);
		String displayName = displayName(user);
		PublicKeyCredentialCreationOptions options = this.relyingParty.startRegistration(StartRegistrationOptions
			.builder()
			.user(UserIdentity.builder()
				.name(user.getEmail())
				.displayName(displayName)
				.id(PasskeyCredentials.handle(user.getId()))
				.build())
			.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
				.residentKey(ResidentKeyRequirement.REQUIRED)
				.userVerification(UserVerificationRequirement.REQUIRED)
				.build())
			.timeout(BROWSER_TIMEOUT_MS)
			.build());
		try {
			session.setAttribute(REGISTRATION_KEY, new Pending(options.toJson(), user.getId(), expiry()));
			return publicKeyPart(options.toCredentialsCreateJson());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not write passkey options", ex);
		}
	}

	/**
	 * Step 2 of adding a passkey.
	 * @param credentialJson RegistrationResponseJSON from the browser
	 * @throws ApiException 400 {@code passkey_invalid}
	 */
	@Transactional
	public PasskeyResponse register(AuthUser principal, HttpSession session, String name, String credentialJson) {
		User user = this.actors.active(principal);
		Pending pending = take(session, REGISTRATION_KEY)
			.filter((p) -> p.userId().equals(user.getId()))
			.orElseThrow(() -> registrationFailed("No passkey request found or it expired. Please try again"));
		try {
			PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(pending.json());
			PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential = PublicKeyCredential
				.parseRegistrationResponseJson(credentialJson);
			RegistrationResult result = this.relyingParty
				.finishRegistration(FinishRegistrationOptions.builder().request(options).response(credential).build());
			String transports = result.getKeyId()
				.getTransports()
				.map((set) -> set.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(",")))
				.orElse(null);
			Passkey passkey = Passkey.create(user.getId(), result.getKeyId().getId().getBytes(),
					result.getPublicKeyCose().getBytes(), result.getSignatureCount(), transports,
					(name != null) ? name : DEFAULT_NAME, this.clock.instant());
			return PasskeyResponse.from(this.passkeys.saveAndFlush(passkey));
		}
		catch (IOException | RegistrationFailedException ex) {
			logger.info("Passkey registration failed for user {}: {}", user.getId(), ex.getMessage());
			throw registrationFailed("This passkey could not be added");
		}
		catch (DataIntegrityViolationException ex) {
			throw registrationFailed("This passkey is already added");
		}
	}

	@Transactional(readOnly = true)
	public List<PasskeyResponse> list(AuthUser principal) {
		User user = this.actors.active(principal);
		return this.passkeys.findAllByUserIdOrderByCreatedAtAsc(user.getId())
			.stream()
			.map(PasskeyResponse::from)
			.toList();
	}

	/**
	 * @throws ApiException 404 {@code passkey_not_found}
	 */
	@Transactional
	public PasskeyResponse rename(AuthUser principal, UUID id, String name) {
		Passkey passkey = mine(principal, id);
		passkey.rename(name);
		return PasskeyResponse.from(passkey);
	}

	/**
	 * @throws ApiException 404 {@code passkey_not_found}
	 */
	@Transactional
	public void delete(AuthUser principal, UUID id) {
		this.passkeys.delete(mine(principal, id));
	}

	// ---- login ----

	/**
	 * Step 1 of a passkey login. No email: the phone offers the passkeys it has for
	 * this site.
	 * @return PublicKeyCredentialRequestOptionsJSON (binary values in base64url)
	 */
	public String loginOptions(HttpSession session) {
		AssertionRequest request = this.relyingParty.startAssertion(StartAssertionOptions.builder()
			.userVerification(UserVerificationRequirement.REQUIRED)
			.timeout(BROWSER_TIMEOUT_MS)
			.build());
		try {
			session.setAttribute(LOGIN_KEY, new Pending(request.toJson(), null, expiry()));
			return publicKeyPart(request.toCredentialsGetJson());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not write passkey options", ex);
		}
	}

	/**
	 * Step 2 of a passkey login. Works only for ACTIVE users. Every try with a known
	 * passkey is saved in the login history. The transaction is not rolled back by the
	 * 401, so failed tries stay saved.
	 * @param credentialJson AuthenticationResponseJSON from the browser
	 * @return the logged-in user
	 * @throws ApiException 401 {@code passkey_invalid} or 429 {@code too_many_attempts}
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public User login(HttpSession session, String credentialJson, String clientIp) {
		String limitKey = "passkey|" + clientIp;
		if (this.limiters.passkeyLogin().isBlocked(limitKey)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
					"Too many attempts. Please wait and try again later");
		}
		Optional<Pending> pending = take(session, LOGIN_KEY);
		PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> credential;
		try {
			credential = PublicKeyCredential.parseAssertionResponseJson(credentialJson);
		}
		catch (IOException ex) {
			throw loginFailed(limitKey, null, clientIp);
		}
		Optional<Passkey> passkey = this.passkeys.findByCredentialId(credential.getId().getBytes());
		UUID knownUserId = passkey.map(Passkey::getUserId).orElse(null);
		if (pending.isEmpty() || passkey.isEmpty()) {
			throw loginFailed(limitKey, knownUserId, clientIp);
		}
		AssertionResult result;
		try {
			AssertionRequest request = AssertionRequest.fromJson(pending.get().json());
			result = this.relyingParty
				.finishAssertion(FinishAssertionOptions.builder().request(request).response(credential).build());
		}
		catch (IOException | AssertionFailedException ex) {
			logger.info("Passkey login failed for user {}: {}", knownUserId, ex.getMessage());
			throw loginFailed(limitKey, knownUserId, clientIp);
		}
		Optional<User> user = this.users.findById(knownUserId).filter((u) -> u.getStatus() == UserStatus.ACTIVE);
		if (!result.isSuccess() || user.isEmpty()) {
			throw loginFailed(limitKey, knownUserId, clientIp);
		}
		Instant now = this.clock.instant();
		passkey.get().recordUse(result.getSignatureCount(), now);
		user.get().recordLogin(now);
		this.loginEvents.record(knownUserId, true, clientIp, now);
		this.limiters.passkeyLogin().reset(limitKey);
		return user.get();
	}

	/** How many passkeys a user has (for the admin user page). */
	@Transactional(readOnly = true)
	public long count(UUID userId) {
		return this.passkeys.countByUserId(userId);
	}

	private ApiException loginFailed(String limitKey, UUID knownUserId, String clientIp) {
		this.limiters.passkeyLogin().record(limitKey);
		if (knownUserId != null) {
			this.loginEvents.record(knownUserId, false, clientIp, this.clock.instant());
		}
		return new ApiException(HttpStatus.UNAUTHORIZED, "passkey_invalid", "This passkey could not be used");
	}

	private Passkey mine(AuthUser principal, UUID id) {
		User user = this.actors.active(principal);
		return this.passkeys.findByIdAndUserId(id, user.getId())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "passkey_not_found", "Passkey not found"));
	}

	/** Reads and removes the pending ceremony (one use only); empty if missing or expired. */
	private Optional<Pending> take(HttpSession session, String key) {
		Object value = session.getAttribute(key);
		session.removeAttribute(key);
		return (value instanceof Pending pending && this.clock.instant().isBefore(pending.expiresAt()))
				? Optional.of(pending) : Optional.empty();
	}

	private Instant expiry() {
		return this.clock.instant().plus(Duration.ofMinutes(CEREMONY_MINUTES));
	}

	private static String displayName(User user) {
		String name = String.join(" ",
				java.util.stream.Stream.of(user.getFirstName(), user.getLastName())
					.filter((part) -> part != null && !part.isBlank())
					.toList());
		return name.isBlank() ? user.getEmail() : name;
	}

	/** The library wraps options as {"publicKey": {...}}; the browser API wants the inner object. */
	private static String publicKeyPart(String wrapped) throws IOException {
		return JSON.readTree(wrapped).get("publicKey").toString();
	}

	private static ApiException registrationFailed(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "passkey_invalid", message);
	}

	/**
	 * A ceremony waiting for its step 2, kept in the session.
	 * @param userId the user adding a passkey (null for login)
	 */
	record Pending(String json, UUID userId, Instant expiresAt) implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

	}

}
