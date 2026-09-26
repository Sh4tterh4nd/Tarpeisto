package io.kellermann.bigcontainers.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BookingHistoryView(
        UUID id, String action, UUID actorUserId, Instant occurredAt, Map<String, Object> snapshot) {}
