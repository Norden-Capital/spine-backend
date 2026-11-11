package com.nordencapital.spine.config;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * CORS settings, bound from {@code app.cors.*} (env var {@code APP_CORS_ALLOWED_ORIGINS}).
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {

	public CorsProperties {
		if (allowedOrigins != null) {
			allowedOrigins.forEach((origin) -> RequiredSetting.check("app.cors.allowed-origins", origin));
		}
	}

}
