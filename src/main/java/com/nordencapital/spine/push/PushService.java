package com.nordencapital.spine.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.nordencapital.spine.auth.AttemptLimiter;
import com.nordencapital.spine.auth.AuthUser;
import com.nordencapital.spine.common.ApiException;
import com.nordencapital.spine.user.Actors;
import com.nordencapital.spine.user.User;

/**
 * Web Push: the devices (push subscriptions) of each user, and the phone notification
 * for every new alert.
 * <ul>
 * <li>One subscription per device (endpoint). Subscribing again moves the endpoint to the
 * current user (a shared phone).</li>
 * <li>Each new alert row ({@code AlertService.add}) is pushed to that user's devices
 * AFTER the commit, on a background thread: a push never fails or slows the action.
 * Company alerts reach everyone because AlertService adds one row per active user.</li>
 * <li>404/410 from the push service = the device is gone: deleted. Other errors count;
 * deleted after {@value #MAX_FAILURES} in a row.</li>
 * <li>Endpoints must be https on a known push service (no requests to other servers).</li>
 * </ul>
 * Without VAPID keys push is off: the endpoints answer 503 {@code push_disabled} and
 * alerts are only in the app.
 */
@Service
public class PushService {

	static final int MAX_FAILURES = 5;

	static final int MAX_DEVICES = 20;

	/** Push services of Chrome/Android, Safari/iOS/macOS, Firefox and Edge. */
	private static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "android.googleapis.com",
			".push.apple.com", ".push.services.mozilla.com", ".notify.windows.com");

	private static final Logger logger = LoggerFactory.getLogger(PushService.class);

	private final JdbcTemplate jdbc;

	private final Actors actors;

	private final PushSender sender;

	private final JsonMapper json;

	private final Clock clock;

	private final VapidKeys keys;

	private final ExecutorService executor;

	/** "Send a test" is limited: 5 per user per 10 minutes. */
	private final AttemptLimiter testLimiter;

	public PushService(JdbcTemplate jdbc, Actors actors, PushSender sender, JsonMapper json, Clock clock,
			PushProperties properties, @Value("${app.push.async:true}") boolean async) {
		this.jdbc = jdbc;
		this.actors = actors;
		this.sender = sender;
		this.json = json;
		this.clock = clock;
		this.keys = VapidKeys.from(properties);
		this.executor = async ? Executors.newFixedThreadPool(2, daemonThreads()) : null;
		this.testLimiter = new AttemptLimiter(5, Duration.ofMinutes(10), clock);
		logger.info((this.keys != null) ? "Web Push is on" : "Web Push is off (no VAPID keys)");
	}

	@PreDestroy
	void stop() {
		if (this.executor != null) {
			this.executor.shutdown();
		}
	}

	public boolean isEnabled() {
		return this.keys != null;
	}

	// ---- API ----

	/** @throws ApiException 503 {@code push_disabled} */
	public String publicKey(AuthUser principal) {
		this.actors.active(principal);
		return enabledKeys().publicKeyText();
	}

	/**
	 * Saves this device for me (or moves it to me). Same endpoint again: keys updated.
	 * @throws ApiException 400 {@code validation_error}, 503 {@code push_disabled}
	 */
	@Transactional
	public Status subscribe(AuthUser principal, String endpoint, String p256dh, String auth, String userAgent) {
		User user = this.actors.active(principal);
		enabledKeys();
		String cleanEndpoint = checkEndpoint(endpoint);
		checkKey(p256dh, 65, "keys.p256dh");
		checkKey(auth, 16, "keys.auth");
		String label = (userAgent == null || userAgent.isBlank()) ? null
				: userAgent.strip().substring(0, Math.min(200, userAgent.strip().length()));
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.update("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, label, created_at, "
				+ "updated_at, failures) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0) ON CONFLICT (endpoint) DO UPDATE SET "
				+ "user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, label = EXCLUDED.label, "
				+ "updated_at = EXCLUDED.updated_at, failures = 0", UUID.randomUUID(), user.getId(), cleanEndpoint,
				p256dh.strip(), auth.strip(), label, now, now);
		// Keep the newest devices only.
		this.jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND id NOT IN (SELECT id FROM "
				+ "push_subscriptions WHERE user_id = ? ORDER BY updated_at DESC, id LIMIT " + MAX_DEVICES + ")",
				user.getId(), user.getId());
		return new Status(true, true);
	}

	/**
	 * Removes this device from my list. Not mine or unknown: nothing happens.
	 * @throws ApiException 503 {@code push_disabled}
	 */
	@Transactional
	public void unsubscribe(AuthUser principal, String endpoint) {
		User user = this.actors.active(principal);
		enabledKeys();
		if (endpoint != null) {
			this.jdbc.update("DELETE FROM push_subscriptions WHERE endpoint = ? AND user_id = ?", endpoint.strip(),
					user.getId());
		}
	}

	/**
	 * Is this device subscribed for me?
	 * @throws ApiException 503 {@code push_disabled}
	 */
	@Transactional(readOnly = true)
	public Status status(AuthUser principal, String endpoint) {
		User user = this.actors.active(principal);
		enabledKeys();
		boolean subscribed = endpoint != null && !endpoint.isBlank() && Boolean.TRUE.equals(this.jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM push_subscriptions WHERE endpoint = ? AND user_id = ?)", Boolean.class,
				endpoint.strip(), user.getId()));
		return new Status(true, subscribed);
	}

	/**
	 * Sends "Spine test" to all my devices now.
	 * @return how many devices it was sent to (accepted by the push service)
	 * @throws ApiException 503 {@code push_disabled}, 429 {@code too_many_attempts}
	 */
	public TestResult test(AuthUser principal) {
		User user = this.actors.active(principal);
		enabledKeys();
		if (!this.testLimiter.tryAcquire(user.getId().toString())) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
					"Too many test notifications. Try again in a few minutes");
		}
		Message message = new Message("Spine test", "Notifications work on this device.", "/alerts", "spine-test");
		List<Device> devices = devices(user.getId());
		int sent = deliver(user.getId(), message, devices);
		return new TestResult(devices.size(), sent);
	}

	// ---- alerts ----

	/**
	 * Called by AlertService when it added an alert row: pushes it to that user's devices
	 * after the commit (nothing is sent if the transaction rolls back).
	 */
	public void alertAdded(UUID userId, UUID alertId, String title, String body, String link) {
		if (!isEnabled()) {
			return;
		}
		Message message = new Message(title, body, (link != null && !link.isBlank()) ? link : "/alerts",
				alertId.toString());
		Runnable task = () -> {
			try {
				deliver(userId, message, devices(userId));
			}
			catch (RuntimeException ex) {
				logger.warn("Push for alert {} failed: {}", alertId, ex.getMessage());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					run(task);
				}
			});
		}
		else {
			run(task);
		}
	}

	private void run(Runnable task) {
		if (this.executor == null) {
			task.run();
			return;
		}
		try {
			this.executor.execute(task);
		}
		catch (RuntimeException ex) {
			logger.warn("Push not queued: {}", ex.getMessage());
		}
	}

	/** Sends to each device and keeps the list clean. @return how many accepted it */
	private int deliver(UUID userId, Message message, List<Device> devices) {
		if (devices.isEmpty()) {
			return 0;
		}
		byte[] payload = payload(message, unread(userId));
		int accepted = 0;
		for (Device device : devices) {
			int status;
			try {
				status = this.sender.send(new PushSender.Target(device.endpoint(), device.p256dh(), device.auth()),
						payload);
			}
			catch (RuntimeException ex) {
				logger.warn("Push to device {} failed: {}", device.id(), ex.getMessage());
				status = -1;
			}
			if (status >= 200 && status < 300) {
				accepted++;
				this.jdbc.update("UPDATE push_subscriptions SET last_ok_at = ?, failures = 0 WHERE id = ?",
						Timestamp.from(this.clock.instant()), device.id());
			}
			else if (status == 404 || status == 410) {
				this.jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", device.id());
				logger.info("Push device {} is gone ({}): removed", device.id(), status);
			}
			else {
				this.jdbc.update("UPDATE push_subscriptions SET failures = failures + 1 WHERE id = ?", device.id());
				int removed = this.jdbc.update("DELETE FROM push_subscriptions WHERE id = ? AND failures >= ?",
						device.id(), MAX_FAILURES);
				logger.warn("Push to device {} got {}{}", device.id(), status,
						(removed > 0) ? ": removed after " + MAX_FAILURES + " failures" : "");
			}
		}
		return accepted;
	}

	/**
	 * The JSON the service worker gets: title, body, url (a path in the app), tag (the
	 * alert id: a new push with the same tag replaces the old one), unread (for the badge).
	 */
	byte[] payload(Message message, int unread) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("title", cut(message.title(), 200));
		fields.put("body", cut(message.body(), 1000));
		fields.put("url", message.url());
		fields.put("tag", message.tag());
		fields.put("unread", unread);
		return this.json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
	}

	private int unread(UUID userId) {
		Integer count = this.jdbc.queryForObject(
				"SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, userId);
		return (count != null) ? count : 0;
	}

	private List<Device> devices(UUID userId) {
		return this.jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ? "
				+ "ORDER BY created_at", (rs, row) -> new Device(rs.getObject("id", UUID.class),
						rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")),
				userId);
	}

	// ---- checks ----

	private VapidKeys enabledKeys() {
		if (this.keys == null) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "push_disabled",
					"Phone notifications are not set up on this server");
		}
		return this.keys;
	}

	/** https, a known push service, no user info, at most 2048 characters. */
	static String checkEndpoint(String endpoint) {
		if (endpoint == null || endpoint.isBlank() || endpoint.strip().length() > 2048) {
			throw invalid("endpoint: required, at most 2048 characters");
		}
		String clean = endpoint.strip();
		URI uri;
		try {
			uri = URI.create(clean);
		}
		catch (IllegalArgumentException ex) {
			throw invalid("endpoint: not a valid URL");
		}
		String host = (uri.getHost() != null) ? uri.getHost().toLowerCase(Locale.ROOT) : "";
		boolean known = PUSH_HOSTS.stream()
			.anyMatch((allowed) -> allowed.startsWith(".") ? host.endsWith(allowed) : host.equals(allowed));
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || !known) {
			throw invalid("endpoint: must be an https URL of a browser push service");
		}
		return clean;
	}

	private static void checkKey(String value, int bytes, String field) {
		byte[] decoded;
		try {
			decoded = (value != null && value.length() <= 128) ? WebPushCrypto.base64Url(value) : null;
		}
		catch (IllegalArgumentException ex) {
			decoded = null;
		}
		if (decoded == null || decoded.length != bytes) {
			throw invalid(field + ": must be base64url of " + bytes + " bytes");
		}
		if (bytes == 65) {
			try {
				WebPushCrypto.publicKey(decoded);
			}
			catch (IllegalArgumentException ex) {
				throw invalid(field + ": not a valid P-256 key");
			}
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
	}

	private static String cut(String text, int max) {
		if (text == null) {
			return "";
		}
		return (text.length() <= max) ? text : text.substring(0, max - 1) + "…";
	}

	private static java.util.concurrent.ThreadFactory daemonThreads() {
		AtomicInteger count = new AtomicInteger();
		return (task) -> {
			Thread thread = new Thread(task, "push-" + count.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	// ---- types ----

	/** @param enabled push works on this server; @param subscribed this device is on for me */
	public record Status(boolean enabled, boolean subscribed) {
	}

	/** @param devices my devices; @param sent how many the push services accepted */
	public record TestResult(int devices, int sent) {
	}

	record Message(String title, String body, String url, String tag) {
	}

	private record Device(UUID id, String endpoint, String p256dh, String auth) {
	}

}
