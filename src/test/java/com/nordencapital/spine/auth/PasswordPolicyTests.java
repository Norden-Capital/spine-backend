package com.nordencapital.spine.auth;

import org.junit.jupiter.api.Test;

import com.nordencapital.spine.common.ApiException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PasswordPolicyTests {

	@Test
	void acceptsTwelveToOneHundredTwentyEightCharacters() {
		assertThatCode(() -> PasswordPolicy.check("a".repeat(12))).doesNotThrowAnyException();
		assertThatCode(() -> PasswordPolicy.check("a".repeat(128))).doesNotThrowAnyException();
	}

	@Test
	void rejectsTooShortTooLongAndNull() {
		for (String password : new String[] { null, "", "a".repeat(11), "a".repeat(129) }) {
			assertThatExceptionOfType(ApiException.class).isThrownBy(() -> PasswordPolicy.check(password))
				.satisfies((ex) -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("weak_password"));
		}
	}

}
