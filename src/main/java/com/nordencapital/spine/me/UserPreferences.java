package com.nordencapital.spine.me;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Display settings of one user (table user_preferences). No row = {@link Preferences#DEFAULTS}.
 */
@Entity
@Table(name = "user_preferences")
public class UserPreferences {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "date_format", nullable = false, length = 10)
	private String dateFormat;

	/** IANA id like "America/Toronto". Null = automatic (the device's time zone). */
	@Column(name = "time_zone", length = 64)
	private String timeZone;

	@Column(nullable = false, length = 10)
	private String theme;

	@Column(nullable = false, length = 10)
	private String font;

	@Column(name = "text_size", nullable = false, length = 10)
	private String textSize;

	/** Private wallet: default currency, USD or MAD. */
	@Column(name = "wallet_currency", nullable = false, length = 3)
	private String walletCurrency = "USD";

	/** Private wallet: 1 USD = this many MAD. */
	@Column(name = "wallet_usd_rate", nullable = false, precision = 12, scale = 4)
	private BigDecimal walletUsdRate = Preferences.DEFAULT_USD_RATE;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserPreferences() {
	}

	UserPreferences(UUID userId) {
		this.userId = userId;
	}

	void update(Preferences values, Instant now) {
		this.dateFormat = values.dateFormat();
		this.timeZone = values.timeZone();
		this.theme = values.theme();
		this.font = values.font();
		this.textSize = values.textSize();
		// Wallet fields: null = keep (older apps do not send them).
		if (values.walletCurrency() != null) {
			this.walletCurrency = values.walletCurrency();
		}
		if (values.walletUsdRate() != null) {
			this.walletUsdRate = new BigDecimal(values.walletUsdRate());
		}
		this.updatedAt = now;
	}

	Preferences toPreferences() {
		return new Preferences(this.dateFormat, this.timeZone, this.theme, this.font, this.textSize,
				this.walletCurrency, Preferences.rateText(this.walletUsdRate));
	}

}
