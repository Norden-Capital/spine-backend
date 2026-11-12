package com.nordencapital.spine.logo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.common.Websites;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * Logos of transaction websites, fetched once per domain and kept in the table logos.
 * A failure is kept too ("NONE") and tried again after a few days, so we never hammer a
 * site. Fetches run in the background (2 threads), one at a time per domain.
 */
@Service
public class LogoService implements DisposableBean {

	/** A found logo is fetched again after this (lazily, on use). */
	static final Duration REFRESH_OK = Duration.ofDays(30);

	/** A domain without a usable logo is tried again after this. */
	static final Duration RETRY_NONE = Duration.ofDays(3);

	/** How long a first request waits for a fetch in progress. */
	private static final Duration WAIT = Duration.ofSeconds(25);

	private static final Logger logger = LoggerFactory.getLogger(LogoService.class);

	private final JdbcTemplate jdbc;

	private final LogoFetcher fetcher;

	private final Actors actors;

	private final Clock clock;

	private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(200), (runnable) -> {
				Thread thread = new Thread(runnable, "logo-fetch");
				thread.setDaemon(true);
				return thread;
			});

	private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

	public LogoService(JdbcTemplate jdbc, LogoFetcher fetcher, Actors actors, Clock clock) {
		this.jdbc = jdbc;
		this.fetcher = fetcher;
		this.actors = actors;
		this.clock = clock;
	}

	/** API path of a domain's logo (relative, under /api). */
	public static String path(String domain) {
		return "/api/logos/" + domain;
	}

	/**
	 * logoUrl for each domain: null when we already know there is no logo (until the
	 * retry time), else the logo path (the first request may fetch it).
	 */
	public Map<String, String> logoUrls(Collection<String> domains) {
		Set<String> wanted = new HashSet<>(domains);
		wanted.remove(null);
		Map<String, String> urls = new HashMap<>(); // allows get(null)
		if (wanted.isEmpty()) {
			return urls;
		}
		Set<String> none = new HashSet<>(this.jdbc.queryForList(
				"SELECT domain FROM logos WHERE status = 'NONE' AND retry_after > ? AND domain = ANY (?)", String.class,
				Timestamp.from(this.clock.instant()), wanted.toArray(new String[0])));
		wanted.stream().filter((domain) -> !none.contains(domain)).forEach((domain) -> urls.put(domain, path(domain)));
		return urls;
	}

	/**
	 * The picture of a transaction: the first of these domains (its own website, then its
	 * category's, then the parent category's) that has a logo, or null (category icon).
	 * @param logoUrls from {@link #logoUrls} for all these domains
	 */
	public static String firstLogo(Map<String, String> logoUrls, String... domains) {
		for (String domain : domains) {
			String url = (domain != null) ? logoUrls.get(domain) : null;
			if (url != null) {
				return url;
			}
		}
		return null;
	}

	/** Starts a background fetch if we have nothing fresh for this domain. Never blocks. */
	public void prefetch(String domain) {
		if (domain != null && find(domain).map((cached) -> cached.isDue(this.clock.instant())).orElse(true)) {
			start(domain);
		}
	}

	/**
	 * The logo of a domain used on a company transaction or on one of my wallet
	 * transactions. Fetches it on the first request.
	 * @throws ApiException 404 {@code logo_not_found}
	 */
	public Cached get(AuthUser principal, String domain) {
		User actor = this.actors.active(principal);
		if (!Objects.equals(Websites.domainOrNull(domain), domain) || !isUsed(domain, actor)) {
			throw notFound();
		}
		Instant now = this.clock.instant();
		Optional<Cached> cached = find(domain);
		if (cached.isPresent() && cached.get().isOk()) {
			if (cached.get().isDue(now)) {
				start(domain);
			}
			return cached.get();
		}
		if (cached.isPresent() && !cached.get().isDue(now)) {
			throw notFound();
		}
		try {
			start(domain).get(WAIT.toSeconds(), TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException | TimeoutException ex) {
			// treated as "no logo now"
		}
		return find(domain).filter(Cached::isOk).orElseThrow(LogoService::notFound);
	}

	private boolean isUsed(String domain, User actor) {
		Boolean used = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM transactions WHERE website = ?) "
				+ "OR EXISTS (SELECT 1 FROM categories WHERE website = ?) "
				+ "OR EXISTS (SELECT 1 FROM wallet_transactions WHERE user_id = ? AND website = ?) "
				+ "OR EXISTS (SELECT 1 FROM wallet_categories WHERE user_id = ? AND website = ?)", Boolean.class,
				domain, domain, actor.getId(), domain, actor.getId(), domain);
		return Boolean.TRUE.equals(used);
	}

	Optional<Cached> find(String domain) {
		List<Cached> rows = this.jdbc.query(
				"SELECT status, content_type, data, retry_after FROM logos WHERE domain = ?",
				(rs, row) -> new Cached(rs.getString("status"), rs.getString("content_type"), rs.getBytes("data"),
						rs.getTimestamp("retry_after").toInstant()),
				domain);
		return rows.stream().findFirst();
	}

	/** One fetch per domain at a time; later callers share it. */
	private CompletableFuture<Void> start(String domain) {
		CompletableFuture<Void> mine = new CompletableFuture<>();
		CompletableFuture<Void> running = this.inFlight.putIfAbsent(domain, mine);
		if (running != null) {
			return running;
		}
		try {
			this.pool.execute(() -> {
				try {
					fetchAndStore(domain);
				}
				finally {
					this.inFlight.remove(domain, mine);
					mine.complete(null);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			this.inFlight.remove(domain, mine);
			mine.complete(null);
		}
		return mine;
	}

	private void fetchAndStore(String domain) {
		Instant now = this.clock.instant();
		Optional<LogoFetcher.Logo> logo;
		try {
			logo = this.fetcher.fetch(domain);
		}
		catch (RuntimeException ex) {
			logger.debug("Logo fetch failed for {}", domain, ex);
			logo = Optional.empty();
		}
		if (logo.isPresent()) {
			save(domain, "OK", logo.get().contentType(), logo.get().data(), now, now.plus(REFRESH_OK));
		}
		else if (find(domain).map(Cached::isOk).orElse(false)) {
			// A refresh failed: keep the old logo, try again later.
			this.jdbc.update("UPDATE logos SET retry_after = ? WHERE domain = ?", Timestamp.from(now.plus(RETRY_NONE)),
					domain);
		}
		else {
			save(domain, "NONE", null, null, now, now.plus(RETRY_NONE));
		}
	}

	private void save(String domain, String status, String contentType, byte[] data, Instant now, Instant retryAfter) {
		this.jdbc.update("INSERT INTO logos (domain, status, content_type, data, fetched_at, retry_after) "
				+ "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (domain) DO UPDATE SET status = EXCLUDED.status, "
				+ "content_type = EXCLUDED.content_type, data = EXCLUDED.data, fetched_at = EXCLUDED.fetched_at, "
				+ "retry_after = EXCLUDED.retry_after", domain, status, contentType, data, Timestamp.from(now),
				Timestamp.from(retryAfter));
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "logo_not_found", "No logo for this website");
	}

	@Override
	public void destroy() {
		this.pool.shutdownNow();
	}

	/** A cached row: status OK (with image) or NONE. */
	public record Cached(String status, String contentType, byte[] data, Instant retryAfter) {

		public boolean isOk() {
			return "OK".equals(this.status);
		}

		boolean isDue(Instant now) {
			return !now.isBefore(this.retryAfter);
		}

	}

}
