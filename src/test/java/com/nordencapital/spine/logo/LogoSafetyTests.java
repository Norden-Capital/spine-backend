package com.nordencapital.spine.logo;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guards of the logo fetcher, image checks and icon link parsing (no network).
 */
class LogoSafetyTests {

	@Test
	void onlyPublicAddressesArePublic() throws Exception {
		for (String ip : new String[] { "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
				"169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1",
				"::1", "::", "fe80::1", "fd00::1", "fc00::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
				"64:ff9b::a00:1", "2002:a00:1::1", "2001:db8::1" }) {
			assertThat(SafeDns.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
		}
		for (String ip : new String[] { "8.8.8.8", "1.1.1.1", "172.32.0.1", "2606:4700:4700::1111",
				"2002:808:808::1" }) {
			assertThat(SafeDns.isPublic(InetAddress.getByName(ip))).as(ip).isTrue();
		}
	}

	@Test
	void dnsRefusesANameWithAnyPrivateAddress() throws Exception {
		SafeDns dns = new SafeDns((host) -> new InetAddress[] { InetAddress.getByName("93.184.216.34"),
				InetAddress.getByName("10.0.0.5") });
		assertThatThrownBy(() -> dns.resolve("evil.example")).isInstanceOf(UnknownHostException.class)
			.hasMessageContaining("Refused");
		SafeDns ok = new SafeDns((host) -> new InetAddress[] { InetAddress.getByName("93.184.216.34") });
		assertThat(ok.resolve("example.com")).hasSize(1);
	}

	@Test
	void fetcherNeverConnectsToAPrivateAddress() {
		List<String> looked = new CopyOnWriteArrayList<>();
		HttpLogoFetcher fetcher = new HttpLogoFetcher((host) -> {
			looked.add(host);
			return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
		});
		assertThat(fetcher.lookUp("rebind.example.com").pageRead()).isFalse();
		assertThat(looked).isNotEmpty().allMatch("rebind.example.com"::equals);

		HttpLogoFetcher metadata = new HttpLogoFetcher(
				(host) -> new InetAddress[] { InetAddress.getByName("169.254.169.254") });
		assertThat(metadata.lookUp("metadata.example.com").pageRead()).isFalse();
	}

	@Test
	void onlyHttpOnNormalPortsAndNoIpLiterals() {
		assertThat(HttpLogoFetcher.allowed(URI.create("https://example.com/a.png"))).isTrue();
		assertThat(HttpLogoFetcher.allowed(URI.create("http://example.com:80/"))).isTrue();
		assertThat(HttpLogoFetcher.allowed(URI.create("https://example.com:443/"))).isTrue();
		for (String bad : new String[] { "ftp://example.com/", "file:///etc/passwd", "https://example.com:8080/",
				"http://example.com:22/", "http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/",
				"http://[::1]/", "http://user@example.com/", "gopher://example.com/" }) {
			assertThat(HttpLogoFetcher.allowed(URI.create(bad))).as(bad).isFalse();
		}
	}

	@Test
	void findsIconLinksBiggestFirst() {
		String html = """
				<html><head>
				<link rel="icon" href="/favicon-32.png" sizes="32x32">
				<link rel='apple-touch-icon' href='/apple.png'>
				<link rel="icon" type="image/svg+xml" href="/icon.svg">
				<link rel="mask-icon" href="/mask.svg">
				<link href="https://cdn.example.com/icon-512.png?v=1&amp;x=2" rel="icon" sizes="512x512">
				<link rel="stylesheet" href="/style.css">
				<link rel="icon" href="javascript:alert(1)">
				</head></html>""";
		List<IconLinks.Candidate> found = IconLinks.parse(html, URI.create("https://example.com/home/"));
		assertThat(found).extracting(IconLinks.Candidate::uri)
			.containsExactly(URI.create("https://cdn.example.com/icon-512.png?v=1&x=2"),
					URI.create("https://example.com/apple.png"), URI.create("https://example.com/favicon-32.png"),
					URI.create("https://example.com/icon.svg"));
	}

	@Test
	void readsManifestIcons() {
		String html = "<head><link rel=\"manifest\" href=\"/site.webmanifest\"></head>";
		URI manifest = IconLinks.manifest(html, URI.create("https://example.com/"));
		assertThat(manifest).isEqualTo(URI.create("https://example.com/site.webmanifest"));
		String json = """
				{"name":"X","icons":[{"src":"\\/img\\/icon-192.png","sizes":"192x192","type":"image/png"},
				{"src":"icon-512.png","sizes":"512x512"},{"src":"logo.svg","sizes":"any"},
				{"src":"javascript:alert(1)","sizes":"64x64"}]}""";
		assertThat(IconLinks.manifestIcons(json, URI.create("https://cdn.example.com/app/site.webmanifest")))
			.extracting(IconLinks.Candidate::uri)
			.containsExactly(URI.create("https://cdn.example.com/app/icon-512.png"),
					URI.create("https://cdn.example.com/img/icon-192.png"));
		assertThat(IconLinks.manifest("<head></head>", URI.create("https://example.com/"))).isNull();
	}

	@Test
	void saysWhyNothingWasFound() {
		HttpLogoFetcher fetcher = new HttpLogoFetcher((host) -> new InetAddress[] { InetAddress.getByName("10.0.0.7") });
		LogoFetcher.Lookup lookup = fetcher.lookUp("inside.example.com");
		assertThat(lookup.pageRead()).isFalse();
		assertThat(lookup.iconUrl()).isNull();
		assertThat(lookup.reason()).contains("inside.example.com/ refused: private address");
	}

}
