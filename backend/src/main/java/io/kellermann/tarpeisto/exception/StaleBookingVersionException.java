package io.kellermann.tarpeisto.exception;

/** A booking command was based on an obsolete booking version. */
public class StaleBookingVersionException extends ApplicationException {
    public StaleBookingVersionException() {
        super("STALE_BOOKING_VERSION", "The booking changed. Refresh and try again.");
    }
}
