package io.kellermann.tarpeisto.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. Covers specification section 6.4. */
class ConsumableStockTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorAcceptsAZeroQuantity() {
        ConsumableStock balance = new ConsumableStock(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, NOW, NOW);
        assertThat(balance.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructorRejectsANegativeQuantity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsumableStock(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.TEN.negate(),
                        NOW,
                        NOW));
    }

    @Test
    void constructorRejectsANullContainerAssetId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConsumableStock(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, BigDecimal.TEN, NOW, NOW));
    }
}
