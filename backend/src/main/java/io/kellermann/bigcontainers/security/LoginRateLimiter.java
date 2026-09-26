package io.kellermann.bigcontainers.security;

import io.kellermann.bigcontainers.config.LoginRateLimitProperties;
import io.kellermann.bigcontainers.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory login rate limiter, keyed by the caller (see {@link SessionAuthenticationService} for
 * the exact key: normalized username combined with the client address, computed identically
 * whether or not the username belongs to a real account, so the limiter itself cannot be used to
 * enumerate accounts). Only failed attempts count against the limit; a successful login resets its
 * key immediately.
 *
 * <p>Single-JVM only, per the small self-hosted deployment target: state is not shared across
 * application instances. A later phase can move this to a shared store if multi-instance
 * deployments need it; nothing in this contract - the {@link #checkAllowed(String)}/{@link
 * #recordFailedAttempt(String)}/{@link #reset(String)} shape - would need to change.
 */
@Component
public class LoginRateLimiter {

    private final Clock clock;
    private final LoginRateLimitProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock, LoginRateLimitProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** @throws RateLimitExceededException if {@code key} has exhausted its attempt budget. */
    public void checkAllowed(String key) {
        Window window = windows.get(key);
        if (window != null && !isExpired(window) && window.attempts() >= properties.maxAttempts()) {
            throw new RateLimitExceededException("Too many login attempts. Try again later.");
        }
    }

    public void recordFailedAttempt(String key) {
        Instant now = clock.instant();
        windows.compute(key, (ignoredKey, existing) -> {
            if (existing == null || isExpired(existing)) {
                return new Window(now, 1);
            }
            return new Window(existing.windowStart(), existing.attempts() + 1);
        });
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private boolean isExpired(Window window) {
        return window.windowStart().plus(properties.window()).isBefore(clock.instant());
    }

    private record Window(Instant windowStart, int attempts) {}
}
