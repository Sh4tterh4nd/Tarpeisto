package io.kellermann.tarpeisto.service;

import java.util.List;

public record BookingReservationPreviewView(
        boolean reservable, List<BookingConflictView> conflicts, List<BookingConflictView> warnings, long version) {
    public BookingReservationPreviewView(boolean reservable, List<BookingConflictView> conflicts) {
        this(reservable, conflicts, List.of(), 0);
    }
}
