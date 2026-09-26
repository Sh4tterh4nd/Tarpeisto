package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.BookingHistoryView;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BookingHistoryResponse(
        UUID id, String action, UUID actorUserId, Instant occurredAt, Map<String, Object> snapshot) {
    static BookingHistoryResponse from(BookingHistoryView v) {
        return new BookingHistoryResponse(v.id(), v.action(), v.actorUserId(), v.occurredAt(), v.snapshot());
    }
}
