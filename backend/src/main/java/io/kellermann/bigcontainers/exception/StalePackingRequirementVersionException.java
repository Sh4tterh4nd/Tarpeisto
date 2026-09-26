package io.kellermann.bigcontainers.exception;

/** A packing requirement changed after the caller last read it. */
public class StalePackingRequirementVersionException extends ApplicationException {
    public StalePackingRequirementVersionException() {
        super("STALE_PACKING_REQUIREMENT_VERSION", "The packing requirement changed. Refresh and try again.");
    }
}
