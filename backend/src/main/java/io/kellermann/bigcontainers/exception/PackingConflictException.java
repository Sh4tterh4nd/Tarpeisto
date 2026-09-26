package io.kellermann.bigcontainers.exception;

/** A packing target or active name is already in use. */
public class PackingConflictException extends ApplicationException {
    public PackingConflictException(String message) {
        super("PACKING_CONFLICT", message);
    }
}
