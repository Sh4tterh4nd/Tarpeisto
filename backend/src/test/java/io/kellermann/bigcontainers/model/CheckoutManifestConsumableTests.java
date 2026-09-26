package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutManifestConsumableTests {

    @Test
    void carriedStockIsNeverCountedAsSeparatelyConsumed() {
        CheckoutManifestConsumable line = new CheckoutManifestConsumable(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                new BigDecimal("3"),
                CheckoutConsumableSemantics.CARRIED_IN_CONTAINER,
                "{}");
        assertThat(line.getConsumedQuantity()).isZero();
    }

    @Test
    void accountingClosesFurtherUnusedReturns() {
        CheckoutManifestConsumable line = new CheckoutManifestConsumable(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                new BigDecimal("3"),
                CheckoutConsumableSemantics.SEPARATELY_ISSUED,
                "{}");
        line.addReturned(BigDecimal.ONE);
        line.account(java.time.Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(line.getConsumedQuantity()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> line.addReturned(BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separatelyIssuedReturnCannotExceedFrozenIssuedQuantity() {
        CheckoutManifestConsumable line = new CheckoutManifestConsumable(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                new BigDecimal("3.000"),
                CheckoutConsumableSemantics.SEPARATELY_ISSUED,
                "{}");

        line.addReturned(new BigDecimal("1.250"));

        assertThat(line.getReturnedQuantity()).isEqualByComparingTo("1.250");
        assertThatThrownBy(() -> line.addReturned(new BigDecimal("1.751")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Return exceeds issued amount.");
    }
}
