package com.nordencapital.spine.me;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Display settings, used for GET and PUT /api/me/preferences (all fields every time).
 * The time zone is checked in {@link MeService} (must be a known IANA id or null).
 */
public record Preferences(
		@Schema(allowableValues = { "DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD" }) @NotNull
		@Pattern(regexp = "DD/MM/YYYY|MM/DD/YYYY|YYYY-MM-DD", message = "must be DD/MM/YYYY, MM/DD/YYYY or YYYY-MM-DD") String dateFormat,
		@Schema(description = "IANA time zone id, e.g. America/Toronto. null = automatic (the device's zone).",
				nullable = true) @Size(max = 64) String timeZone,
		@Schema(allowableValues = { "LIGHT", "DARK", "SYSTEM" }) @NotNull
		@Pattern(regexp = "LIGHT|DARK|SYSTEM", message = "must be LIGHT, DARK or SYSTEM") String theme,
		@Schema(allowableValues = { "GEIST", "INTER", "MANROPE", "SYSTEM" }) @NotNull
		@Pattern(regexp = "GEIST|INTER|MANROPE|SYSTEM", message = "must be GEIST, INTER, MANROPE or SYSTEM") String font,
		@Schema(allowableValues = { "SMALL", "NORMAL", "LARGE" }) @NotNull
		@Pattern(regexp = "SMALL|NORMAL|LARGE", message = "must be SMALL, NORMAL or LARGE") String textSize) {

	public static final Preferences DEFAULTS = new Preferences("DD/MM/YYYY", null, "SYSTEM", "GEIST", "NORMAL");

}
