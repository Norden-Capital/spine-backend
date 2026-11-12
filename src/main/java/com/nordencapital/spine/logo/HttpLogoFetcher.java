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
import java.util.Locale;
import java.util.Optional;
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
 * Reads a site's home page (and its web app manifest) to find the icon URL it declares.
 * The image itself is loaded by the browser, not by the server (many sites block servers).
 * <p>
 * SSRF guards: http/https only, ports 80/443 only, no IP literals, DNS answers with any
 * non-public address are refused (on every hop: {@link SafeDns}), at most 3 redirects,
 * 5 s connect/read timeouts and a 20 s budget per domain, at most 1 MB per response, no
 * proxy, no cookies. Compressed (gzip) answers are unpacked by the client.
 */
@Component
class HttpLogoFetcher implements LogoFetcher, DisposableBean {

	static final int MAX_BYTES = 1024 * 1024;

	static final int MAX_REDIRECTS = 3;

	private static final Timeout TIMEOUT = Timeout.ofSeconds(5);

	private static final Duration BUDGET = Duration.ofSeconds(20);

	/** A common browser identity: many sites refuse unknown clients. */
	static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
			+ "Chrome/131.0.0.0 Safari/537.36";

	/** What went wrong in the current fetch (one list per thread). */
	private final ThreadLocal<java.util.List<String>> failures = ThreadLocal.withInitial(java.util.ArrayList::new);

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
			.setUserAgent(USER_AGENT)
			.setDefaultHeaders(java.util.List.of(
					new org.apache.hc.core5.http.message.BasicHeader("Accept-Language", "en-US,en;q=0.9")))
			.build();
	}

	@Override
	public Lookup lookUp(String domain) {
		if (!Websites.isDomain(domain)) {
			return new Lookup(false, null, "not a domain");
		}
		this.failures.get().clear();
		Instant deadline = Instant.now().plus(BUDGET);
		Optional<Body> page = get(URI.create("https://" + domain + "/"), false, deadline);
		if (page.isEmpty()) {
			page = get(URI.create("http://" + domain + "/"), false, deadline);
		}
		if (page.isEmpty()) {
			return new Lookup(false, null, lastFailure());
		}
		String html = new String(page.get().data(), StandardCharsets.UTF_8);
		java.util.List<IconLinks.Candidate> declared = new java.util.ArrayList<>(IconLinks.parse(html, page.get().uri()));
		URI manifest = IconLinks.manifest(html, page.get().uri());
		if (manifest != null) {
			get(manifest, false, deadline).ifPresent((json) -> declared
				.addAll(IconLinks.manifestIcons(new String(json.data(), StandardCharsets.UTF_8), json.uri())));
		}
		declared.sort(java.util.Comparator.comparingInt(IconLinks.Candidate::declaredSize).reversed());
		String best = declared.stream()
			.map((candidate) -> candidate.uri().toString())
			.filter(LogoService::isWebUrl)
			.findFirst()
			.orElse(null);
		return new Lookup(true, best, (best == null) ? "the page declares no icon" : null);
	}

	/** Why the last lookup on this thread could not read the page. */
	String lastFailure() {
		java.util.List<String> list = this.failures.get();
		return list.isEmpty() ? "unknown" : String.join("; ", list.subList(0, Math.min(list.size(), 6)));
	}

	private void fail(URI uri, String why) {
		this.failures.get().add(uri.getHost() + uri.getPath() + " " + why);
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
			if (!allowed(uri)) {
				fail(uri, "not allowed (scheme, port or IP address)");
				return Optional.empty();
			}
			if (Instant.now().isAfter(deadline)) {
				fail(uri, "time budget over");
				return Optional.empty();
			}
			HttpGet request = new HttpGet(uri);
			request.setHeader("Accept",
					image ? "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8"
							: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			ClassicHttpResponse response = null;
			try {
				response = this.http.executeOpen(null, request, null);
				int code = response.getCode();
				if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
					Header location = response.getFirstHeader("Location");
					if (location == null) {
						fail(uri, "redirect without Location");
						return Optional.empty();
					}
					uri = uri.resolve(new URI(location.getValue().strip().replace(" ", "%20")));
					continue;
				}
				if (code != 200 || response.getEntity() == null) {
					fail(uri, "HTTP " + code);
					return Optional.empty();
				}
				byte[] data = read(response.getEntity(), image, deadline);
				if (data == null) {
					fail(uri, "too big or too slow");
				}
				return (data != null) ? Optional.of(new Body(uri, data)) : Optional.empty();
			}
			catch (IOException | URISyntaxException | IllegalArgumentException ex) {
				fail(uri, (ex instanceof SafeDns.RefusedAddressException) ? "refused: private address"
						: ex.getClass().getSimpleName());
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
		fail(start, "too many redirects");
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
