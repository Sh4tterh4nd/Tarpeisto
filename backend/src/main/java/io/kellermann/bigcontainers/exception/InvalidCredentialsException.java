package io.kellermann.bigcontainers.exception;

/**
 * A login attempt failed. Thrown identically whether the username is unknown, the account has no
 * usable local credential, the password is wrong, or the account is disabled - the message,
 * status, and body are the same in every case so a caller cannot distinguish "wrong password"
 * from "unknown account" (specification section 28, ADR-0003).
 */
public class InvalidCredentialsException extends ApplicationException {

    private static final String ERROR_CODE = "INVALID_CREDENTIALS";

    public InvalidCredentialsException(String message) {
        super(ERROR_CODE, message);
    }
}
