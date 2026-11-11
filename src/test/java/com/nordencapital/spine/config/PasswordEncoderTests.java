package com.nordencapital.spine.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTests {

	private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

	@Test
	void usesArgon2AndNeverStoresThePassword() {
		String hash = this.encoder.encode("correct horse battery");
		assertThat(hash).startsWith("{argon2@SpringSecurity_v5_8}$argon2id$");
		assertThat(hash).doesNotContain("correct horse battery");
		assertThat(this.encoder.matches("correct horse battery", hash)).isTrue();
		assertThat(this.encoder.matches("wrong horse battery", hash)).isFalse();
	}

	@Test
	void allOneHundredTwentyEightCharactersCount() {
		// BCrypt would ignore everything after 72 bytes; Argon2 does not.
		String password = "a".repeat(127) + "b";
		String hash = this.encoder.encode(password);
		assertThat(this.encoder.matches(password, hash)).isTrue();
		assertThat(this.encoder.matches("a".repeat(128), hash)).isFalse();
	}

}
