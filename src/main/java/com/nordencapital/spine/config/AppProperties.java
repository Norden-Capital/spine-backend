package com.nordencapital.spine.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * App settings bound from {@code app.*} (see application.yml and .env.example).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotBlank String frontendUrl, @Valid @NotNull Mail mail,
		@Valid @NotNull Bootstrap bootstrap, @Valid @NotNull Auth auth) {

	public AppProperties {
		RequiredSetting.check("app.frontend-url", frontendUrl);
	}

	public record Mail(@NotBlank String from, String resendApiKey, boolean requireResend) {

		public Mail {
			RequiredSetting.check("app.mail.from", from);
			RequiredSetting.check("app.mail.resend-api-key", resendApiKey);
		}

		public boolean hasResendApiKey() {
			return this.resendApiKey != null && !this.resendApiKey.isBlank();
		}

	}

	public record Bootstrap(String adminEmail) {

		public Bootstrap {
			RequiredSetting.check("app.bootstrap.admin-email", adminEmail);
		}

	}

	public record Auth(@NotNull Duration inviteTtl, @NotNull Duration resetTtl, @Min(1) int loginMaxFailures,
			@NotNull Duration loginLockTime, @Min(1) int forgotPasswordMaxRequests,
			@NotNull Duration forgotPasswordWindow) {
	}

}
