package io.kellermann.tarpeisto.exception;

/** An asset move was based on an out-of-date placement version. */
public class StalePlacementVersionException extends ApplicationException {
    public StalePlacementVersionException() {
        super("STALE_PLACEMENT_VERSION", "The asset was moved by another user. Refresh and try again.");
    }
}
