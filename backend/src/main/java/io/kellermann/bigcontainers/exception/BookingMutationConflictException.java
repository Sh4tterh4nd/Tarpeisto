package io.kellermann.bigcontainers.exception;

public class BookingMutationConflictException extends ApplicationException {
    public BookingMutationConflictException() {
        super("BOOKING_MUTATION_CONFLICT", "This creation command was already used with different booking details.");
    }
}
