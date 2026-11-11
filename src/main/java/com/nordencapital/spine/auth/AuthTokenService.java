package com.nordencapital.spine.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nordencapital.spine.config.AppProperties;
import com.nordencapital.spine.user.User;
import com.nordencapital.spine.user.UserStatus;

/**
 * Creates and checks one-time invite and reset tokens.
 * <p>
 * A token is 32 random bytes (base64url). Only its SHA-256 hash is stored. A user has at
 * most one token: a new one deletes the older ones, and using a token deletes it.
 */
@Service
public class AuthTokenService {

	private static final int TOKEN_BYTES = 32;

	/** Longer input cannot be one of our tokens; skip hashing it. */
	private static final int MAX_TOKEN_LENGTH = 100;

	private final SecureRandom random = new SecureRandom();

	private final AuthTokenRepository tokens;

	private final AppProperties.Auth settings;

	private final Clock clock;

	public AuthTokenService(AuthTokenRepository tokens, AppProperties properties, Clock clock) {
		this.tokens = tokens;
		this.settings = properties.auth();
		this.clock = clock;
	}

	/**
	 * Creates a new token for the user and deletes the user's older tokens.
	 * @return the raw token, to put in the email link (it is not stored)
	 */
	@Transactional
	public String issue(User user, TokenPurpose purpose) {
		this.tokens.deleteAllByUserId(user.getId());
		byte[] bytes = new byte[TOKEN_BYTES];
		this.random.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = this.clock.instant();
		Duration ttl = (purpose == TokenPurpose.INVITE) ? this.settings.inviteTtl() : this.settings.resetTtl();
		this.tokens.save(new AuthToken(user, purpose, hash(rawToken), now.plus(ttl), now));
		return rawToken;
	}

	/**
	 * Finds a token that is known, not expired, and still fits the user's status.
	 */
	@Transactional(readOnly = true)
	public Optional<AuthToken> findValid(String rawToken) {
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		Instant now = this.clock.instant();
		return this.tokens.findByTokenHash(hash(rawToken))
			.filter((token) -> token.getExpiresAt().isAfter(now))
			.filter((token) -> fitsUserStatus(token));
	}

	@Transactional(readOnly = true)
	public boolean hasValidToken(User user, TokenPurpose purpose) {
		return this.tokens.existsByUserIdAndPurposeAndExpiresAtAfter(user.getId(), purpose, this.clock.instant());
	}

	/**
	 * Deletes all tokens of the user (used after a token is consumed or the user is
	 * disabled).
	 */
	@Transactional
	public void deleteAll(User user) {
		this.tokens.deleteAllByUserId(user.getId());
	}

	private static boolean fitsUserStatus(AuthToken token) {
		UserStatus status = token.getUser().getStatus();
		return switch (token.getPurpose()) {
			case INVITE -> status == UserStatus.INVITED;
			case RESET -> status == UserStatus.ACTIVE;
		};
	}

	static String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
