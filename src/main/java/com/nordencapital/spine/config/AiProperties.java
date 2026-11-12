package com.nordencapital.spine.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI comment suggestion ({@code app.ai.*}). Optional: with an empty
 * {@code anthropic-api-key} (env ANTHROPIC_API_KEY) the feature is off.
 * @param maxPerHour suggestions per user per hour
 */
@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String anthropicApiKey, @NotBlank String model, @NotNull Duration timeout,
		@Min(1) int maxPerHour) {

	public boolean enabled() {
		return this.anthropicApiKey != null && !this.anthropicApiKey.isBlank();
	}

}
