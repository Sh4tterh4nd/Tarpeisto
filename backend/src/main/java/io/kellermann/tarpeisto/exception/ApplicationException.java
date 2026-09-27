package io.kellermann.tarpeisto.exception;

/**
 * Base type for the stable Tarpeisto business-exception hierarchy. Every subtype carries a
 * stable {@link #getErrorCode()} that is safe to expose to API clients and does not change across
 * refactors, independent of the (freely revisable) human-readable message.
 */
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;

    protected ApplicationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
