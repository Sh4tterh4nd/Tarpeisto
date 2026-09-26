package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.BookingReservationStatus;
import io.kellermann.bigcontainers.model.BookingStatus;
import io.kellermann.bigcontainers.service.BookingView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        String name,
        String clientText,
        String venueText,
        String notes,
        Instant startsAt,
        Instant endsAt,
        BookingStatus status,
        BookingReservationStatus reservationStatus,
        UUID currentRevisionId,
        UUID createdByUserId,
        long version,
        List<BookingLineResponse> lines) {
    static BookingResponse from(BookingView v) {
        return new BookingResponse(
                v.id(),
                v.name(),
                v.clientText(),
                v.venueText(),
                v.notes(),
                v.startsAt(),
                v.endsAt(),
                v.status(),
                v.reservationStatus(),
                v.currentRevisionId(),
                v.createdByUserId(),
                v.version(),
                v.lines().stream().map(BookingLineResponse::from).toList());
    }
}
