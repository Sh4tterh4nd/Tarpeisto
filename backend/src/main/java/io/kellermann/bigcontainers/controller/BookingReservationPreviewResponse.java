package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.BookingReservationPreviewView;
import java.util.List;

public record BookingReservationPreviewResponse(
        boolean reservable,
        List<BookingConflictResponse> conflicts,
        List<BookingConflictResponse> warnings,
        long version) {
    static BookingReservationPreviewResponse from(BookingReservationPreviewView v) {
        return new BookingReservationPreviewResponse(
                v.reservable(),
                v.conflicts().stream().map(BookingConflictResponse::from).toList(),
                v.warnings().stream().map(BookingConflictResponse::from).toList(),
                v.version());
    }
}
