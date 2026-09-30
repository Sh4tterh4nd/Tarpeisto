package io.kellermann.tarpeisto.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Local fixed-window admission for login and checked-code enumeration. Both budgets and capacity
 * are checked under one monitor before either counter changes. Only expired entries are reclaimed;
 * a full store rejects admission rather than evicting a live protection. Keys retain only a digest,
 * so oversized usernames cannot inflate retained state. This state is per JVM and resets on restart.
 */
final class BoundedSecurityAttemptWindow {
    private final Clock clock;
    private final Duration window;
    private final int capacity;
    private final Map<String, Counter> counters = new HashMap<>();

    BoundedSecurityAttemptWindow(Clock clock, Duration window, int capacity) {
        if (window == null || window.isZero() || window.isNegative() || capacity <= 0) {
            throw new IllegalArgumentException("Security attempt window and capacity must be positive.");
        }
        this.clock = clock;
        this.window = window;
        this.capacity = capacity;
    }

    synchronized boolean admit(String firstKey, int firstBudget, String secondKey, int secondBudget) {
        Instant now = clock.instant();
        counters.values().removeIf(counter -> !now.isBefore(counter.expiresAt()));
        Counter first = counters.get(firstKey);
        Counter second = counters.get(secondKey);
        int required = (first == null ? 1 : 0) + (second == null ? 1 : 0);
        if (firstKey.equals(secondKey)) {
            throw new IllegalArgumentException("Security attempt budgets require distinct keys.");
        }
        if (firstBudget <= 0
                || secondBudget <= 0
                || first != null && first.attempts() >= firstBudget
                || second != null && second.attempts() >= secondBudget
                || required > capacity - counters.size()) {
            return false;
        }
        counters.put(firstKey, increment(first, now));
        counters.put(secondKey, increment(second, now));
        return true;
    }

    private Counter increment(Counter previous, Instant now) {
        return previous == null
                ? new Counter(now.plus(window), 1)
                : new Counter(previous.expiresAt(), previous.attempts() + 1);
    }

    static String key(String... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] bytes = component == null ? new byte[0] : component.getBytes(StandardCharsets.UTF_8);
                // Length framing prevents delimiter collisions in user-controlled inputs.
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private record Counter(Instant expiresAt, int attempts) {}
}
