package com.nordencapital.spine.logo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.nordencapital.spine.common.Websites;

/**
 * Fetches a site's logo ourselves (no third-party logo service, so nobody else learns
 * what we pay for). Order: icon links in the home page (biggest first), then
 * /favicon.ico. The biggest square image wins.
 * <p>
 * SSRF guards: http/https only, ports 80/443 only, no IP literals, DNS answers with any
 * non-public address are refused (on every hop: {@link SafeDns}), at most 3 redirects,
 * 5 s connect/read timeouts and a 20 s budget per domain, at most 1 MB per response,
 * only PNG/JPEG/WebP/ICO images (checked from the bytes), no proxy, no cookies.
 */
@Component
class HttpLogoFetcher implements LogoFetcher, DisposableBean {

	static final int MAX_BYTES = 1024 * 1024;

	static final int MAX_REDIRECTS = 3;

	private static final Timeout TIMEOUT = Timeout.ofSeconds(5);

	private static final Duration BUDGET = Duration.ofSeconds(20);

	private static final int MAX_CANDIDATES = 6;

	/** A square logo this big is good enough: stop looking. */
	private static final int GOOD_SIZE = 128;

	private static final Pattern IPV4 = Pattern.compile("[0-9.]+");

	private static final Logger logger = LoggerFactory.getLogger(HttpLogoFetcher.class);

	private final CloseableHttpClient http;

	HttpLogoFetcher() {
		this(InetAddress::getAllByName);
	}

	HttpLogoFetcher(SafeDns.Lookup lookup) {
		ConnectionConfig connection = ConnectionConfig.custom()
			.setConnectTimeout(TIMEOUT)
			.setSocketTimeout(TIMEOUT)
			.build();
		RequestConfig request = RequestConfig.custom()
			.setResponseTimeout(TIMEOUT)
			.setConnectionRequestTimeout(TIMEOUT)
			.setRedirectsEnabled(false)
			.build();
		this.http = HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(new SafeDns(lookup))
				.setDefaultConnectionConfig(connection)
				.setMaxConnTotal(8)
				.setMaxConnPerRoute(2)
				.build())
			.setDefaultRequestConfig(request)
			.disableRedirectHandling()
			.disableCookieManagement()
			.disableAuthCaching()
			.disableAutomaticRetries()
			.setUserAgent("Mozilla/5.0 (compatible; SpineLogoFetcher/1.0)")
			.build();
	}

	@Override
	public Optional<Logo> fetch(String domain) {
		if (!Websites.isDomain(domain)) {
			return Optional.empty();
		}
		Instant deadline = Instant.now().plus(BUDGET);
		URI home = URI.create("https://" + domain + "/");
		Optional<Body> page = get(home, false, deadline);
		if (page.isEmpty()) {
			page = get(URI.create("http://" + domain + "/"), false, deadline);
		}
		Set<URI> candidates = new LinkedHashSet<>();
		URI base = page.map(Body::uri).orElse(home);
		page.ifPresent((body) -> IconLinks.parse(new String(body.data(), StandardCharsets.UTF_8), body.uri())
			.forEach((candidate) -> candidates.add(candidate.uri())));
		candidates.add(base.resolve("/favicon.ico"));
		candidates.add(home.resolve("/favicon.ico"));

		Logo best = null;
		ImageInfo bestInfo = null;
		for (URI uri : candidates.stream().limit(MAX_CANDIDATES).toList()) {
			Optional<Body> image = get(uri, true, deadline);
			ImageInfo info = image.map((body) -> ImageInfo.detect(body.data())).orElse(null);
			if (info == null) {
				continue;
			}
			if (bestInfo == null || better(info, bestInfo)) {
				best = new Logo(info.contentType(), image.get().data());
				bestInfo = info;
			}
			if (bestInfo.isSquare() && bestInfo.size() >= GOOD_SIZE) {
				break;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Square beats not square, then bigger beats smaller. */
	static boolean better(ImageInfo a, ImageInfo b) {
		if (a.isSquare() != b.isSquare()) {
			return a.isSquare();
		}
		return a.size() > b.size();
	}

	/** True when we may connect to this URL at all (checked on every redirect). */
	static boolean allowed(URI uri) {
		String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
		String host = uri.getHost();
		int port = uri.getPort();
		return (scheme.equals("http") || scheme.equals("https")) && host != null && uri.getUserInfo() == null
				&& (port == -1 || port == 80 || port == 443) && !host.contains(":") && !host.startsWith("[")
				&& !IPV4.matcher(host).matches();
	}

	/**
	 * GET with manual redirects. Images over 1 MB are refused; pages are cut at 1 MB.
	 */
	private Optional<Body> get(URI start, boolean image, Instant deadline) {
		URI uri = start;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (!allowed(uri) || Instant.now().isAfter(deadline)) {
				return Optional.empty();
			}
			HttpGet request = new HttpGet(uri);
			request.setHeader("Accept",
					image ? "image/png,image/webp,image/jpeg,image/x-icon,image/*;q=0.5" : "text/html,*/*;q=0.5");
			ClassicHttpResponse response = null;
			try {
				response = this.http.executeOpen(null, request, null);
				int code = response.getCode();
				if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
					Header location = response.getFirstHeader("Location");
					if (location == null) {
						return Optional.empty();
					}
					uri = uri.resolve(new URI(location.getValue().strip().replace(" ", "%20")));
					continue;
				}
				if (code != 200 || response.getEntity() == null) {
					return Optional.empty();
				}
				byte[] data = read(response.getEntity(), image, deadline);
				return (data != null) ? Optional.of(new Body(uri, data)) : Optional.empty();
			}
			catch (IOException | URISyntaxException | IllegalArgumentException ex) {
				logger.debug("Logo fetch failed for {}: {}", uri, ex.toString());
				return Optional.empty();
			}
			finally {
				// Drop the connection: closing normally would read the rest of a big body.
				request.cancel();
				if (response != null) {
					try {
						response.close();
					}
					catch (IOException ignored) {
						// already cancelled
					}
				}
			}
		}
		return Optional.empty();
	}

	/** At most 1 MB; stops when the time budget is over. */
	private static byte[] read(HttpEntity entity, boolean image, Instant deadline) throws IOException {
		if (image && entity.getContentLength() > MAX_BYTES) {
			return null;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[16 * 1024];
		try (InputStream in = entity.getContent()) {
			int n;
			while ((n = in.read(buffer)) != -1) {
				if (Instant.now().isAfter(deadline)) {
					return null;
				}
				int room = MAX_BYTES - out.size();
				if (n > room) {
					if (image) {
						return null;
					}
					out.write(buffer, 0, room);
					break;
				}
				out.write(buffer, 0, n);
			}
		}
		return out.toByteArray();
	}

	@Override
	public void destroy() throws IOException {
		this.http.close();
	}

	private record Body(URI uri, byte[] data) {
	}

}
