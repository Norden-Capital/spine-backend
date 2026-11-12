package com.nordencapital.spine.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebsitesTests {

	@Test
	void keepsOnlyTheBareDomain() {
		assertThat(Websites.normalize("claude.ai")).isEqualTo("claude.ai");
		assertThat(Websites.normalize("https://www.google.com/x")).isEqualTo("google.com");
		assertThat(Websites.normalize("  HTTP://WWW.Example.COM:8080/path?q=1#top  ")).isEqualTo("example.com");
		assertThat(Websites.normalize("shop.example.co.uk/")).isEqualTo("shop.example.co.uk");
		assertThat(Websites.normalize("//cdn.example.com/a.png")).isEqualTo("cdn.example.com");
		assertThat(Websites.normalize("example.com.")).isEqualTo("example.com");
		assertThat(Websites.normalize("www.com")).isEqualTo("www.com");
		assertThat(Websites.normalize("bücher.de")).isEqualTo("xn--bcher-kva.de");
	}

	@Test
	void emptyMeansNone() {
		assertThat(Websites.normalize(null)).isNull();
		assertThat(Websites.normalize("")).isNull();
		assertThat(Websites.normalize("   ")).isNull();
	}

	@Test
	void refusesWhatIsNotADomain() {
		for (String bad : new String[] { "localhost", "http://localhost/", "127.0.0.1", "http://10.0.0.1/x",
				"[::1]", "user@example.com", "https://user:pass@example.com", "exa mple.com", "-bad.com", "bad-.com",
				"example..com", "example.123", "example.com:abc", "javascript:alert(1)", "a".repeat(64) + ".com",
				("a".repeat(60) + ".").repeat(5) + "com" }) {
			assertThatThrownBy(() -> Websites.normalize(bad)).as(bad)
				.isInstanceOfSatisfying(ApiException.class,
						(ex) -> assertThat(ex.getStatus().value()).isEqualTo(400));
		}
		assertThat(Websites.domainOrNull("x".repeat(Websites.MAX_INPUT + 1))).isNull();
	}

}
