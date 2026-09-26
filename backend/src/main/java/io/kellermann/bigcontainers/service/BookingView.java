package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.BookingReservationStatus;
import io.kellermann.bigcontainers.model.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingView(
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
        List<BookingLineView> lines) {}
