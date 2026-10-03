package io.kellermann.tarpeisto.exception;

public final class StalePackingDetailsVersionException extends ApplicationException {
    public StalePackingDetailsVersionException() {
        super("STALE_PACKING_DETAILS_VERSION", "This asset changed. Reload the packing details and try again.");
    }
}
