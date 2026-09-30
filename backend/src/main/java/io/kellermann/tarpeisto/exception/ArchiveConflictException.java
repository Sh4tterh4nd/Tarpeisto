package io.kellermann.tarpeisto.exception;

public final class ArchiveConflictException extends ApplicationException {
    public ArchiveConflictException(String message) {
        super("ARCHIVE_CONFLICT", message);
    }
}
