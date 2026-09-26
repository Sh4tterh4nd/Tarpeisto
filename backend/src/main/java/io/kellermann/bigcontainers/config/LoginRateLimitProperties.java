package io.kellermann.bigcontainers.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Controls login rate limiting (ADR-0003, implementation plan section 3.2). Bound from {@code
 * bigcontainers.security.login-rate-limit.*}.
 *
 * @param maxAttempts the number of failed login attempts allowed for one rate-limit key within
 *     {@code window} before further attempts are rejected with {@code 429 Too Many Requests}
 * @param window the sliding window over which failed attempts are counted; a successful login
 *     resets the count for its key immediately
 */
@ConfigurationProperties(prefix = "bigcontainers.security.login-rate-limit")
@Validated
public record LoginRateLimitProperties(
        @Positive int maxAttempts, @NotNull Duration window) {}
