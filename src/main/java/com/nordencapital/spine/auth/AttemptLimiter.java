package com.nordencapital.spine.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory limiter: at most {@code maxAttempts} per key in a fixed time window
 * that starts at the first attempt. State is lost on restart, which is fine for one
 * instance.
 */
public class AttemptLimiter {

	/** Remove old windows when the map gets this big, so it cannot grow forever. */
	private static final int CLEANUP_SIZE = 10_000;

	private final int maxAttempts;

	private final Duration window;

	private final Clock clock;

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	public AttemptLimiter(int maxAttempts, Duration window, Clock clock) {
		this.maxAttempts = maxAttempts;
		this.window = window;
		this.clock = clock;
	}

	/**
	 * @return true if the key used up all attempts in the current window
	 */
	public boolean isBlocked(String key) {
		Window current = this.windows.get(key);
		return current != null && !current.isOver(this.clock.instant()) && current.count() >= this.maxAttempts;
	}

	/**
	 * Counts one attempt for the key.
	 */
	public void record(String key) {
		Instant now = this.clock.instant();
		cleanUpIfBig(now);
		this.windows.compute(key, (k, current) -> (current == null || current.isOver(now))
				? new Window(now.plus(this.window), 1) : new Window(current.end(), current.count() + 1));
	}

	/**
	 * Counts one attempt if the key is not blocked.
	 * @return false if the key is blocked (nothing is counted)
	 */
	public boolean tryAcquire(String key) {
		if (isBlocked(key)) {
			return false;
		}
		record(key);
		return true;
	}

	public void reset(String key) {
		this.windows.remove(key);
	}

	/** Forgets all keys. */
	public void clear() {
		this.windows.clear();
	}

	private void cleanUpIfBig(Instant now) {
		if (this.windows.size() >= CLEANUP_SIZE) {
			this.windows.values().removeIf((w) -> w.isOver(now));
		}
	}

	private record Window(Instant end, int count) {

		boolean isOver(Instant now) {
			return !now.isBefore(this.end);
		}

	}

}
