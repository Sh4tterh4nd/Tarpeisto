package io.kellermann.tarpeisto.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BookingHistoryView(
        UUID id, String action, UUID actorUserId, Instant occurredAt, Map<String, Object> snapshot) {}
