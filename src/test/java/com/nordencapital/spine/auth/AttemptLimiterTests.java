package com.nordencapital.spine.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptLimiterTests {

	private final MutableClock clock = new MutableClock();

	private final AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), this.clock);

	@Test
	void blocksAfterMaxAttempts() {
		this.limiter.record("k");
		this.limiter.record("k");
		assertThat(this.limiter.isBlocked("k")).isFalse();
		this.limiter.record("k");
		assertThat(this.limiter.isBlocked("k")).isTrue();
	}

	@Test
	void keysAreSeparate() {
		for (int i = 0; i < 3; i++) {
			this.limiter.record("a");
		}
		assertThat(this.limiter.isBlocked("a")).isTrue();
		assertThat(this.limiter.isBlocked("b")).isFalse();
	}

	@Test
	void unblocksWhenWindowEnds() {
		for (int i = 0; i < 3; i++) {
			this.limiter.record("k");
		}
		this.clock.advance(Duration.ofMinutes(14));
		assertThat(this.limiter.isBlocked("k")).isTrue();
		this.clock.advance(Duration.ofMinutes(1));
		assertThat(this.limiter.isBlocked("k")).isFalse();
	}

	@Test
	void resetClearsKey() {
		for (int i = 0; i < 3; i++) {
			this.limiter.record("k");
		}
		this.limiter.reset("k");
		assertThat(this.limiter.isBlocked("k")).isFalse();
	}

	@Test
	void tryAcquireCountsUntilBlocked() {
		assertThat(this.limiter.tryAcquire("k")).isTrue();
		assertThat(this.limiter.tryAcquire("k")).isTrue();
		assertThat(this.limiter.tryAcquire("k")).isTrue();
		assertThat(this.limiter.tryAcquire("k")).isFalse();
	}

	static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
