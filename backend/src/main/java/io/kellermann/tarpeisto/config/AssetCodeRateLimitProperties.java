package io.kellermann.tarpeisto.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Admission budgets for permanent checked-code lookups, including malformed and missing codes. */
@ConfigurationProperties(prefix = "tarpeisto.security.asset-code-rate-limit")
@Validated
public record AssetCodeRateLimitProperties(
        @Positive int maxAttempts,
        @NotNull Duration window,
        @Positive int maxClientAttempts,
        @Positive int maxEntries) {
    @AssertTrue(message = "Rate-limit window must be positive.") public boolean isWindowPositive() {
        return window != null && !window.isZero() && !window.isNegative();
    }
}
