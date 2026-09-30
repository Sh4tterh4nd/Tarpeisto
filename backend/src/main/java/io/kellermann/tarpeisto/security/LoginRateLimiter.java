package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.config.LoginRateLimitProperties;
import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Counts every admitted local sign-in attempt, including successful authentication. */
@Component
public class LoginRateLimiter {
    private final BoundedSecurityAttemptWindow attempts;
    private final LoginRateLimitProperties properties;

    public LoginRateLimiter(Clock clock, LoginRateLimitProperties properties) {
        this.properties = properties;
        this.attempts = new BoundedSecurityAttemptWindow(clock, properties.window(), properties.maxEntries());
    }

    public void admit(String username, String clientAddress) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!attempts.admit(
                BoundedSecurityAttemptWindow.key("login-pair", normalized, clientAddress), properties.maxAttempts(),
                BoundedSecurityAttemptWindow.key("login-client", clientAddress), properties.maxClientAttempts())) {
            throw new RateLimitExceededException("Too many login attempts. Try again later.");
        }
    }
}
