package com.nordencapital.spine.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHashTests {

	@Test
	void hashIsSha256Hex() {
		// Known SHA-256 test vector for "abc".
		assertThat(AuthTokenService.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
