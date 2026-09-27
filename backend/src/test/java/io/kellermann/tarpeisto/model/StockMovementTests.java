package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. Covers specification section 6.4. */
class StockMovementTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorAcceptsAValidReceiptMovement() {
        StockMovement movement = movement(BigDecimal.TEN, new BigDecimal("10.000"), StockMovementReason.RECEIPT, null);
        assertThat(movement.getReason()).isEqualTo(StockMovementReason.RECEIPT);
        assertThat(movement.getTransferGroupId()).isNull();
    }

    @Test
    void constructorRejectsAZeroQuantityDelta() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movement(BigDecimal.ZERO, BigDecimal.ZERO, StockMovementReason.RECEIPT, null));
    }

    @Test
    void constructorRejectsANegativeResultingQuantity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movement(
                        BigDecimal.TEN.negate(), BigDecimal.TEN.negate(), StockMovementReason.CONSUMPTION, null));
    }

    @Test
    void constructorRejectsATransferWithoutATransferGroupId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movement(BigDecimal.TEN, BigDecimal.TEN, StockMovementReason.TRANSFER, null));
    }

    @Test
    void constructorRejectsANonTransferWithATransferGroupId() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> movement(BigDecimal.TEN, BigDecimal.TEN, StockMovementReason.RECEIPT, UUID.randomUUID()));
    }

    @Test
    void constructorAcceptsATransferWithATransferGroupId() {
        UUID transferGroupId = UUID.randomUUID();
        StockMovement movement =
                movement(BigDecimal.TEN, BigDecimal.TEN, StockMovementReason.TRANSFER, transferGroupId);
        assertThat(movement.getTransferGroupId()).isEqualTo(transferGroupId);
    }

    @Test
    void constructorRejectsABlankStockUnitLabel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StockMovement(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        "  ",
                        StockMovementReason.RECEIPT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        NOW));
    }

    @Test
    void constructorRejectsANullOccurredAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StockMovement(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        "roll",
                        StockMovementReason.RECEIPT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    void constructorTrimsABlankNoteToNull() {
        StockMovement movement = new StockMovement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.TEN,
                BigDecimal.TEN,
                "roll",
                StockMovementReason.RECEIPT,
                null,
                "   ",
                null,
                null,
                null,
                NOW);
        assertThat(movement.getNote()).isNull();
    }

    private static StockMovement movement(
            BigDecimal delta, BigDecimal resultingQuantity, StockMovementReason reason, UUID transferGroupId) {
        return new StockMovement(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                delta,
                resultingQuantity,
                "roll",
                reason,
                UUID.randomUUID(),
                "a note",
                transferGroupId,
                null,
                null,
                NOW);
    }
}
