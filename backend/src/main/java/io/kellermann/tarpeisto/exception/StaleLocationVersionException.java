package io.kellermann.tarpeisto.exception;

/** A location edit was based on an out-of-date version. */
public class StaleLocationVersionException extends ApplicationException {
    public StaleLocationVersionException() {
        super("STALE_LOCATION_VERSION", "The location was changed by another user. Refresh and try again.");
    }
}
