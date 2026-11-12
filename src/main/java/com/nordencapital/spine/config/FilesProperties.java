package com.nordencapital.spine.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where uploaded proof files are stored ({@code app.files.dir}). Defaults: a local
 * folder in dev, /app/data/files in prod (a Docker named volume). Not a required env
 * variable.
 */
@Validated
@ConfigurationProperties(prefix = "app.files")
public record FilesProperties(@NotBlank String dir) {
}
