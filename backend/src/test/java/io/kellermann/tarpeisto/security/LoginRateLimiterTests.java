package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.config.LoginRateLimitProperties;
import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTests {
    private final MutableClock clock = new MutableClock();

    @Test
    void concurrentAdmissionsCannotExceedPairBudget() throws Exception {
        LoginRateLimiter limiter = limiter(5, 60, 100);
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(24)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Start timeout");
                    return admitted(limiter, "owner", "client");
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }

    @Test
    void concurrentUsernameRotationCannotExceedClientBudget() throws Exception {
        LoginRateLimiter limiter = limiter(5, 5, 100);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(24)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                String username = "rotated-" + i;
                futures.add(executor.submit(() -> {
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Start timeout");
                    return admitted(limiter, username, "client");
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }

    @Test
    void configurationValidationRejectsNonPositiveDurationsAndBudgets() {
        try (var validatorFactory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();
            assertThat(validator.validate(new LoginRateLimitProperties(5, Duration.ZERO, 60, 100)))
                    .isNotEmpty();
            assertThat(validator.validate(new LoginRateLimitProperties(5, Duration.ofSeconds(-1), 60, 100)))
                    .isNotEmpty();
            assertThat(validator.validate(new LoginRateLimitProperties(5, Duration.ofSeconds(1), 0, 100)))
                    .isNotEmpty();
            assertThat(validator.validate(new io.kellermann.tarpeisto.config.AssetCodeRateLimitProperties(
                            120, Duration.ZERO, 600, 100)))
                    .isNotEmpty();
        }
    }

    @Test
    void normalizedPairSharesBudgetButAnotherClientDoesNotLockOutTheUsername() {
        LoginRateLimiter limiter = limiter(2, 10, 100);
        limiter.admit(" Owner ", "a");
        limiter.admit("OWNER", "a");
        assertThatThrownBy(() -> limiter.admit("owner", "a")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.admit("owner", "b")).doesNotThrowAnyException();
    }

    @Test
    void rotatingUsernamesCannotBypassClientBudgetAndDeniedPairDoesNotSpendClientBudget() {
        LoginRateLimiter limiter = limiter(1, 3, 100);
        limiter.admit("one", "a");
        for (int i = 0; i < 10; i++) assertThat(admitted(limiter, "one", "a")).isFalse();
        limiter.admit("two", "a");
        limiter.admit("three", "a");
        assertThat(admitted(limiter, "four", "a")).isFalse();
        assertThat(admitted(limiter, "four", "b")).isTrue();
    }

    @Test
    void deniedClientDoesNotSpendPairBudget() {
        var window = new BoundedSecurityAttemptWindow(clock, Duration.ofMinutes(15), 10);
        assertThat(window.admit("p1", 5, "c1", 1)).isTrue();
        assertThat(window.admit("p2", 1, "c1", 1)).isFalse();
        assertThat(window.admit("p2", 1, "c2", 5)).isTrue();
    }

    @Test
    void exactDeadlineReclaimsExpiredCapacityWithoutExtendingLiveWindows() {
        LoginRateLimiter limiter = limiter(3, 10, 3);
        limiter.admit("one", "a"); // two entries
        limiter.admit("two", "a"); // three entries
        assertThat(admitted(limiter, "three", "a")).isFalse();
        assertThat(admitted(limiter, "one", "b")).isFalse();
        // Full-state denial must not increment the live pair.
        limiter.admit("one", "a");
        limiter.admit("one", "a");
        assertThat(admitted(limiter, "one", "a")).isFalse();
        clock.advance(Duration.ofMinutes(15).minusNanos(1));
        assertThat(admitted(limiter, "new", "b")).isFalse();
        clock.advance(Duration.ofNanos(1));
        assertThat(admitted(limiter, "new", "b")).isTrue();
    }

    @Test
    void framedDigestHasFixedSizeAndCannotConfuseDelimitedInputs() {
        assertThat(BoundedSecurityAttemptWindow.key("pair", "a|b", "c"))
                .hasSize(64)
                .isNotEqualTo(BoundedSecurityAttemptWindow.key("pair", "a", "b|c"));
        assertThat(BoundedSecurityAttemptWindow.key("pair", "x".repeat(1_000_000), "client"))
                .hasSize(64);
    }

    @Test
    void invalidWindowAndCapacityFailImmediately() {
        assertThatThrownBy(() -> limiter(5, 60, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedSecurityAttemptWindow(clock, Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedSecurityAttemptWindow(clock, Duration.ofSeconds(-1), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LoginRateLimiter limiter(int pair, int client, int entries) {
        return new LoginRateLimiter(clock, new LoginRateLimitProperties(pair, Duration.ofMinutes(15), client, entries));
    }

    private boolean admitted(LoginRateLimiter limiter, String username, String client) {
        try {
            limiter.admit(username, client);
            return true;
        } catch (RateLimitExceededException exception) {
            return false;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
