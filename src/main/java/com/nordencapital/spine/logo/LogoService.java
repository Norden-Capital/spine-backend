package com.nordencapital.spine.logo;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Logo URLs of websites. The browser loads the image itself, straight from the brand's
 * site (servers are often blocked, phones are not), trying the URLs in order and showing
 * the category icon when none works. Per domain: the best icon the site declares in its
 * home page (when our server could read it), then {@code /apple-touch-icon.png}, then
 * {@code /favicon.ico}. The declared icon is looked up in the background, once per domain,
 * and kept in the table logos (found: 30 days; page not readable: tried again after 1 day).
 */
@Service
public class LogoService implements DisposableBean {

	/** A looked-up domain is looked up again after this. */
	static final Duration REFRESH_OK = Duration.ofDays(30);

	/** A domain whose page could not be read is tried again after this. */
	static final Duration RETRY_NONE = Duration.ofDays(1);

	private static final Logger logger = LoggerFactory.getLogger(LogoService.class);

	private final JdbcTemplate jdbc;

	private final LogoFetcher fetcher;

	private final Clock clock;

	private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(200), (runnable) -> {
				Thread thread = new Thread(runnable, "logo-lookup");
				thread.setDaemon(true);
				return thread;
			});

	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	public LogoService(JdbcTemplate jdbc, LogoFetcher fetcher, Clock clock) {
		this.jdbc = jdbc;
		this.fetcher = fetcher;
		this.clock = clock;
	}

	/** The URLs every domain gets, even when its page cannot be read. */
	public static List<String> fallbacks(String domain) {
		return List.of("https://" + domain + "/apple-touch-icon.png", "https://" + domain + "/favicon.ico");
	}

	/** Only absolute http/https URLs (never data: or javascript:), at most 2048 characters. */
	public static boolean isWebUrl(String url) {
		if (url == null || url.length() > 2048) {
			return false;
		}
		try {
			URI uri = URI.create(url);
			String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
			return (scheme.equals("https") || scheme.equals("http")) && uri.getHost() != null;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/**
	 * Logo URLs for each domain (ordered, try one after the other). Domains never looked up
	 * (or due again) are looked up in the background; until then they get the fallbacks.
	 */
	public Map<String, List<String>> logoUrls(Collection<String> domains) {
		Set<String> wanted = new HashSet<>(domains);
		wanted.remove(null);
		Map<String, List<String>> urls = new HashMap<>(); // allows get(null)
		if (wanted.isEmpty()) {
			return urls;
		}
		Instant now = this.clock.instant();
		Map<String, String> declared = new HashMap<>();
		Set<String> known = new HashSet<>();
		this.jdbc.query("SELECT domain, icon_url, retry_after FROM logos WHERE domain = ANY (?)", (rs) -> {
			String domain = rs.getString("domain");
			if (rs.getTimestamp("retry_after").toInstant().isAfter(now)) {
				known.add(domain);
			}
			if (isWebUrl(rs.getString("icon_url"))) {
				declared.put(domain, rs.getString("icon_url"));
			}
		}, (Object) wanted.toArray(new String[0]));
		for (String domain : wanted) {
			List<String> list = new ArrayList<>();
			if (declared.containsKey(domain)) {
				list.add(declared.get(domain));
			}
			list.addAll(fallbacks(domain));
			urls.put(domain, List.copyOf(new LinkedHashSet<>(list)));
			if (!known.contains(domain)) {
				start(domain);
			}
		}
		return urls;
	}

	/**
	 * The logo URLs of a transaction or category: its own website first, then the other
	 * websites (subcategory, main category), in order, without repeats. Empty = show the
	 * category icon.
	 */
	public static List<String> chain(Map<String, List<String>> logoUrls, String... domains) {
		LinkedHashSet<String> all = new LinkedHashSet<>();
		for (String domain : domains) {
			if (domain != null) {
				all.addAll(logoUrls.getOrDefault(domain, List.of()));
			}
		}
		return List.copyOf(all);
	}

	/** Starts a background lookup if we have nothing fresh for this domain. Never blocks. */
	public void prefetch(String domain) {
		if (domain == null) {
			return;
		}
		List<Timestamp> retry = this.jdbc.queryForList("SELECT retry_after FROM logos WHERE domain = ?",
				Timestamp.class, domain);
		if (retry.isEmpty() || !retry.get(0).toInstant().isAfter(this.clock.instant())) {
			start(domain);
		}
	}

	/** One lookup per domain at a time. */
	private void start(String domain) {
		if (!this.inFlight.add(domain)) {
			return;
		}
		try {
			this.pool.execute(() -> {
				try {
					lookUpAndStore(domain);
				}
				finally {
					this.inFlight.remove(domain);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			this.inFlight.remove(domain);
		}
	}

	void lookUpAndStore(String domain) {
		Instant now = this.clock.instant();
		LogoFetcher.Lookup lookup;
		try {
			lookup = this.fetcher.lookUp(domain);
		}
		catch (RuntimeException ex) {
			lookup = new LogoFetcher.Lookup(false, null, ex.getClass().getSimpleName());
		}
		String icon = isWebUrl(lookup.iconUrl()) ? lookup.iconUrl() : null;
		if (icon != null) {
			logger.info("Logo {}: icon {}", domain, icon);
		}
		else {
			logger.info("Logo {}: {} ({}), the browser tries /apple-touch-icon.png and /favicon.ico", domain,
					lookup.pageRead() ? "no declared icon" : "page not readable", lookup.reason());
		}
		this.jdbc.update("INSERT INTO logos (domain, status, icon_url, fetched_at, retry_after) VALUES (?, ?, ?, ?, ?) "
				+ "ON CONFLICT (domain) DO UPDATE SET status = EXCLUDED.status, icon_url = EXCLUDED.icon_url, "
				+ "fetched_at = EXCLUDED.fetched_at, retry_after = EXCLUDED.retry_after", domain,
				lookup.pageRead() ? "OK" : "NONE", icon, Timestamp.from(now),
				Timestamp.from(now.plus(lookup.pageRead() ? REFRESH_OK : RETRY_NONE)));
	}

	@Override
	public void destroy() {
		this.pool.shutdownNow();
	}

}
