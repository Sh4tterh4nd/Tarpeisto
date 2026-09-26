package io.kellermann.bigcontainers.exception;

/**
 * A request was syntactically well-formed but violates a business rule that transport-level
 * (JSR-380) validation cannot express, such as a cross-organization reference or an invariant
 * that depends on other persisted state.
 */
public class ValidationFailedException extends ApplicationException {

    private static final String ERROR_CODE = "VALIDATION_FAILED";

    public ValidationFailedException(String message) {
        super(ERROR_CODE, message);
    }
}
