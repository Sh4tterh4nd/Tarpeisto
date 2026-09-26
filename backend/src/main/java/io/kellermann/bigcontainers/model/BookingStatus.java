package io.kellermann.bigcontainers.model;

public enum BookingStatus {
    DRAFT,
    RESERVED,
    CHECKED_OUT,
    RETURNED_AUDITS_PENDING,
    REVIEW_REQUIRED,
    COMPLETED,
    CANCELLED
}
