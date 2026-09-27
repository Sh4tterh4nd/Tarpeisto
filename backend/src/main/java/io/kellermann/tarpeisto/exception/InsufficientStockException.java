package io.kellermann.tarpeisto.exception;

/**
 * An operation would take a {@code ConsumableStock} balance negative (specification section 6.4:
 * "No operation may make a balance negative"). Thrown uniformly whether the targeted balance
 * already exists with too little quantity, or does not exist yet at all - the latter is
 * semantically zero stock, so the caller sees the same outcome either way.
 */
public class InsufficientStockException extends ApplicationException {

    private static final String ERROR_CODE = "INSUFFICIENT_STOCK";

    public InsufficientStockException(String message) {
        super(ERROR_CODE, message);
    }
}
