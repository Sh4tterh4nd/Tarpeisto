package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.config.LoginRateLimitProperties;
import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. */
class LoginRateLimiterTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginRateLimiter rateLimiter =
            new LoginRateLimiter(clock, new LoginRateLimitProperties(3, Duration.ofMinutes(15)));

    @Test
    void allowsAttemptsUpToTheConfiguredMaximum() {
        assertThatCode(() -> rateLimiter.checkAllowed("key")).doesNotThrowAnyException();
        rateLimiter.recordFailedAttempt("key");
        assertThatCode(() -> rateLimiter.checkAllowed("key")).doesNotThrowAnyException();
        rateLimiter.recordFailedAttempt("key");
        assertThatCode(() -> rateLimiter.checkAllowed("key")).doesNotThrowAnyException();
        rateLimiter.recordFailedAttempt("key");
    }

    @Test
    void rejectsOnceTheMaximumIsExceeded() {
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");

        assertThatThrownBy(() -> rateLimiter.checkAllowed("key")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        rateLimiter.recordFailedAttempt("key-a");
        rateLimiter.recordFailedAttempt("key-a");
        rateLimiter.recordFailedAttempt("key-a");

        assertThatThrownBy(() -> rateLimiter.checkAllowed("key-a")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> rateLimiter.checkAllowed("key-b")).doesNotThrowAnyException();
    }

    @Test
    void resetClearsAccumulatedFailures() {
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");

        rateLimiter.reset("key");

        assertThatCode(() -> rateLimiter.checkAllowed("key")).doesNotThrowAnyException();
    }

    @Test
    void aNewWindowAfterTheConfiguredDurationForgivesPriorFailures() {
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");
        rateLimiter.recordFailedAttempt("key");
        assertThatThrownBy(() -> rateLimiter.checkAllowed("key")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofMinutes(16));

        assertThatCode(() -> rateLimiter.checkAllowed("key")).doesNotThrowAnyException();
    }

    /** A small, test-local mutable clock; not a generic utility, used only by this test. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
