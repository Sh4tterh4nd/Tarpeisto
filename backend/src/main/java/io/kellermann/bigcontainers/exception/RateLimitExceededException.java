package io.kellermann.bigcontainers.exception;

/** Too many failed login attempts for a rate-limit key within its configured window. */
public class RateLimitExceededException extends ApplicationException {

    private static final String ERROR_CODE = "RATE_LIMIT_EXCEEDED";

    public RateLimitExceededException(String message) {
        super(ERROR_CODE, message);
    }
}
