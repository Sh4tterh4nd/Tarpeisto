package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TemporaryAccessRateLimiterTests {
    @Test
    void countsSuccessfulExchangesAcrossTokensAndBoundsSharedInvitation() {
        var limiter = new TemporaryAccessRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 30; i++) limiter.attempt("same-client", "token-" + i);
        assertThatThrownBy(() -> limiter.attempt("same-client", "new-token"))
                .isInstanceOf(RateLimitExceededException.class);
        for (int i = 0; i < 120; i++) limiter.attempt("client-" + i, "shared-token");
        assertThatThrownBy(() -> limiter.attempt("different-client", "shared-token"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void clearsTheWindowWithoutKeepingOldClients() {
        var base = Instant.EPOCH;
        var current = new java.util.concurrent.atomic.AtomicReference<>(base);
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return current.get();
            }
        };
        var limiter = new TemporaryAccessRateLimiter(clock);
        for (int i = 0; i < 30; i++) limiter.attempt("client", "token");
        current.set(base.plus(Duration.ofMinutes(10)));
        limiter.attempt("client", "token");
    }
}
