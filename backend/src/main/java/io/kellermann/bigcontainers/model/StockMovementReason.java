package io.kellermann.bigcontainers.model;

/**
 * The initial set of {@link StockMovement} reasons (specification section 6.4). Fixed and
 * exhaustive for this task's scope - a later phase that needs a new reason (for example, a
 * dedicated write-off) adds a constant here and widens the {@code ck_stock_movement_reason} check
 * constraint in a new migration.
 */
public enum StockMovementReason {
    RECEIPT,
    TRANSFER,
    EVENT_ISSUE,
    EVENT_RETURN,
    CONSUMPTION,
    AUDIT_ADJUSTMENT,
    MANUAL_ADJUSTMENT
}
