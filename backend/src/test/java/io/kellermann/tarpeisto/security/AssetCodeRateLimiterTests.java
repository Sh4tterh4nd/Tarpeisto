package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.config.AssetCodeRateLimitProperties;
import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import io.kellermann.tarpeisto.model.OrganizationRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetCodeRateLimiterTests {
    @Test
    void userBudgetCannotBeBypassedByRotatingClientAddressButOrganizationsAreIndependent() {
        var limiter = limiter(2, 10);
        UUID user = UUID.randomUUID();
        var principal = principal(user, UUID.randomUUID());
        limiter.admit(principal, "a");
        limiter.admit(principal, "b");
        assertThatThrownBy(() -> limiter.admit(principal, "c")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.admit(principal(user, UUID.randomUUID()), "c"))
                .doesNotThrowAnyException();
    }

    @Test
    void clientBudgetCannotBeBypassedByRotatingUsersOrOrganizations() {
        var limiter = limiter(10, 2);
        limiter.admit(principal(UUID.randomUUID(), UUID.randomUUID()), "a");
        limiter.admit(principal(UUID.randomUUID(), UUID.randomUUID()), "a");
        var next = principal(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> limiter.admit(next, "a")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.admit(next, "b")).doesNotThrowAnyException();
    }

    private AssetCodeRateLimiter limiter(int userBudget, int clientBudget) {
        return new AssetCodeRateLimiter(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                new AssetCodeRateLimitProperties(userBudget, Duration.ofMinutes(1), clientBudget, 100));
    }

    private TarpeistoPrincipal principal(UUID user, UUID org) {
        return new TarpeistoPrincipal(user, "viewer", "Viewer", org, OrganizationRole.VIEWER);
    }
}
