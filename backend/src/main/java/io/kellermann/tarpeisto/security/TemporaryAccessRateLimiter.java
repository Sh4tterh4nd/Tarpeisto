package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.exception.RateLimitExceededException;
import io.kellermann.tarpeisto.service.TemporaryAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Counts all exchanges, successful and failed; bounded state and one atomic check/increment. */
@Component
public class TemporaryAccessRateLimiter {
    private final Clock clock;
    private final Map<String, Attempts> attempts = new HashMap<>();

    public TemporaryAccessRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void attempt(String client, String token) {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(e -> !now.isBefore(e.getValue().until()));
        String remote = "client:" + client;
        String invitation = "invitation:" + TemporaryAccessService.digest(token == null ? "" : token);
        if (attempts.size() > 10_000 || count(remote) >= 30 || count(invitation) >= 120)
            throw new RateLimitExceededException("Too many invitation attempts. Try again later.");
        add(remote, now);
        add(invitation, now);
    }

    private int count(String key) {
        var entry = attempts.get(key);
        return entry == null ? 0 : entry.count();
    }

    private void add(String key, Instant now) {
        var old = attempts.get(key);
        attempts.put(key, new Attempts(count(key) + 1, old == null ? now.plus(Duration.ofMinutes(10)) : old.until()));
    }

    private record Attempts(int count, Instant until) {}
}
