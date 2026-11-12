package com.nordencapital.spine.logo;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real sites: the server reads their page and finds the declared icon URL (the browser
 * loads the image). Needs the internet, so it runs only on demand:
 * {@code ./mvnw test -Dtest=LiveLogoChecks -Dspine.liveLogos=true}.
 */
@EnabledIfSystemProperty(named = "spine.liveLogos", matches = "true")
class LiveLogoChecks {

	private final HttpLogoFetcher fetcher = new HttpLogoFetcher();

	@ParameterizedTest
	@ValueSource(strings = { "contabo.com", "google.com", "claude.ai", "hostinger.com", "github.com" })
	void readablePagesGiveADeclaredIcon(String domain) {
		LogoFetcher.Lookup lookup = this.fetcher.lookUp(domain);
		System.out.println("LIVE " + domain + " -> " + lookup);
		assertThat(lookup.pageRead()).as(domain + ": " + lookup.reason()).isTrue();
		assertThat(LogoService.isWebUrl(lookup.iconUrl())).as(domain + ": " + lookup).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "temu.com", "chime.com" })
	void otherSitesStillGetTheStandardUrls(String domain) {
		LogoFetcher.Lookup lookup = this.fetcher.lookUp(domain);
		System.out.println("LIVE " + domain + " -> " + lookup);
		assertThat(lookup.iconUrl() == null || LogoService.isWebUrl(lookup.iconUrl())).isTrue();
		assertThat(LogoService.fallbacks(domain)).containsExactly("https://" + domain + "/apple-touch-icon.png",
				"https://" + domain + "/favicon.ico");
	}

}
