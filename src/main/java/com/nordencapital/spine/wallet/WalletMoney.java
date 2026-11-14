package com.nordencapital.spine.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.common.Totals;
import com.nordencapital.spine.me.Preferences;

/**
 * Wallet currencies (USD, MAD only in the PRIVATE wallet): my default currency and my own
 * rate (1 USD = rate MAD) from my preferences, and the conversion used for totals. Totals
 * are converted when read, never stored; round once (2 decimals, HALF_UP) after summing.
 */
@Component
public class WalletMoney {

	public static final String USD = "USD";

	public static final String MAD = "MAD";

	/** Scale of converted amounts before the final rounding (MAD to USD divides). */
	private static final int WORK_SCALE = 10;

	private final JdbcTemplate jdbc;

	public WalletMoney(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** My default currency and rate (no preferences row = USD, 10). */
	public Settings settings(UUID userId) {
		return this.jdbc
			.query("SELECT wallet_currency, wallet_usd_rate FROM user_preferences WHERE user_id = ?",
					(rs, row) -> new Settings(rs.getString("wallet_currency"), rs.getBigDecimal("wallet_usd_rate")),
					userId)
			.stream()
			.findFirst()
			.orElse(new Settings(USD, Preferences.DEFAULT_USD_RATE));
	}

	/** The currency of a request: the given one, else {@code fallback}. */
	static String pick(String requested, String fallback) {
		return (requested != null) ? requested : fallback;
	}

	/** "$1234.50" or "1234.50 MAD" (alert and error texts). */
	static String format(BigDecimal amount, String currency) {
		String money = Totals.money(amount);
		return MAD.equals(currency) ? money + " MAD" : "$" + money;
	}

	/**
	 * @param currency the default currency: every total is in it
	 * @param usdRate 1 USD = usdRate MAD
	 */
	public record Settings(String currency, BigDecimal usdRate) {

		/** The rate as API text, e.g. "10.00". */
		public String usdRateText() {
			return Preferences.rateText(this.usdRate);
		}

		/** {@code amount} in {@code from} as my default currency (not rounded). */
		public BigDecimal toDefault(BigDecimal amount, String from) {
			return convert(amount, from, this.currency);
		}

		/** USD to MAD = x * rate; MAD to USD = x / rate (not rounded). */
		public BigDecimal convert(BigDecimal amount, String from, String to) {
			if (from.equals(to)) {
				return amount;
			}
			if (USD.equals(from)) {
				return amount.multiply(this.usdRate);
			}
			return amount.divide(this.usdRate, WORK_SCALE, RoundingMode.HALF_UP);
		}

	}

}
