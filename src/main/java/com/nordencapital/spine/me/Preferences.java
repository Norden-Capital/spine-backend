package com.nordencapital.spine.me;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Display settings, used for GET and PUT /api/me/preferences (all fields every time,
 * except the two wallet fields: null or left out = keep the saved value).
 * The time zone and the rate are checked in {@link MeService}.
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
		@Pattern(regexp = "SMALL|NORMAL|LARGE", message = "must be SMALL, NORMAL or LARGE") String textSize,
		@Schema(allowableValues = { "USD", "MAD" }, nullable = true,
				description = "Private wallet: default currency (new items, and all wallet totals). "
						+ "Save: null or left out = keep. Default USD.")
		@Pattern(regexp = "USD|MAD", message = "must be USD or MAD") String walletCurrency,
		@Schema(nullable = true, example = "10.00",
				description = "Private wallet: my own rate, 1 USD = this many MAD. More than 0, at most 1000, "
						+ "max 4 decimals. Save: null or left out = keep. Default \"10.00\".")
		@Pattern(regexp = RATE_PATTERN, message = "must be a rate like 10.00 (more than 0, at most 1000, max 4 decimals)")
		String walletUsdRate) {

	/** Shape of a rate text; the value (more than 0, at most 1000) is checked in the service. */
	public static final String RATE_PATTERN = "(0|[1-9][0-9]{0,3})(\\.[0-9]{1,4})?";

	public static final BigDecimal DEFAULT_USD_RATE = new BigDecimal("10");

	public static final BigDecimal MAX_USD_RATE = new BigDecimal("1000");

	public static final Preferences DEFAULTS = new Preferences("DD/MM/YYYY", null, "SYSTEM", "GEIST", "NORMAL", "USD",
			rateText(DEFAULT_USD_RATE));

	/** A rate as text: at least 2 decimals, at most 4 ("10.00", "10.125", "10.1234"). */
	public static String rateText(BigDecimal rate) {
		BigDecimal stripped = rate.stripTrailingZeros();
		return rate.setScale(Math.min(4, Math.max(2, stripped.scale())), java.math.RoundingMode.HALF_UP).toPlainString();
	}

}
