package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.config.AssetCodeRateLimitProperties;
import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class AssetCodeRateLimiter {
    private final BoundedSecurityAttemptWindow attempts;
    private final AssetCodeRateLimitProperties properties;

    public AssetCodeRateLimiter(Clock clock, AssetCodeRateLimitProperties properties) {
        this.properties = properties;
        this.attempts = new BoundedSecurityAttemptWindow(clock, properties.window(), properties.maxEntries());
    }

    public void admit(TarpeistoPrincipal principal, String clientAddress) {
        principal.requirePermanent();
        if (!attempts.admit(
                BoundedSecurityAttemptWindow.key(
                        "code-user",
                        principal.organizationId().toString(),
                        principal.userId().toString()),
                properties.maxAttempts(),
                BoundedSecurityAttemptWindow.key("code-client", clientAddress),
                properties.maxClientAttempts())) {
            throw new RateLimitExceededException("Too many code lookups. Try again later.");
        }
    }
}
