package com.nordencapital.spine.logo;

import java.util.Optional;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real sites the users entered must get a logo. Needs the internet, so it runs only on
 * demand: {@code ./mvnw test -Dtest=LiveLogoChecks -Dspine.liveLogos=true}.
 */
@EnabledIfSystemProperty(named = "spine.liveLogos", matches = "true")
class LiveLogoChecks {

	private final HttpLogoFetcher fetcher = new HttpLogoFetcher();

	@ParameterizedTest
	@ValueSource(strings = { "contabo.com", "temu.com", "google.com", "claude.ai", "hostinger.com", "github.com" })
	void realSitesGetALogo(String domain) {
		Optional<LogoFetcher.Logo> logo = this.fetcher.fetch(domain);
		assertThat(logo).as(domain + ": " + this.fetcher.lastFailure()).isPresent();
		assertThat(ImageInfo.detect(logo.get().data())).as(domain).isNotNull();
	}

}
