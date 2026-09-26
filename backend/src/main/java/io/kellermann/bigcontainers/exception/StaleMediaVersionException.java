package io.kellermann.bigcontainers.exception;

/** A media metadata edit was based on an obsolete optimistic version. */
public class StaleMediaVersionException extends ApplicationException {
    public StaleMediaVersionException() {
        super("STALE_MEDIA_VERSION", "This image changed elsewhere. Refresh and try again.");
    }
}
