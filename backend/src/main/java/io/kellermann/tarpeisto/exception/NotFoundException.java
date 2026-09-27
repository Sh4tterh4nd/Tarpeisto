package io.kellermann.tarpeisto.exception;

/**
 * A requested record does not exist in the caller's authorized scope.
 *
 * <p><strong>404 vs. 403 policy:</strong> services must throw this exception - not let an
 * authorization failure surface - whenever a record exists but belongs to a different
 * organization than the caller's. A missing record and a record that exists in another
 * organization must be indistinguishable to the client; both resolve to this exception and both
 * produce an identical {@code 404} problem response. Server-side logs may still record the
 * distinction for operators.
 */
public class NotFoundException extends ApplicationException {

    private static final String ERROR_CODE = "NOT_FOUND";

    public NotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
