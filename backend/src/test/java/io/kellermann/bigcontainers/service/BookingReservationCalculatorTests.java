package io.kellermann.bigcontainers.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.BookingClaimType;
import io.kellermann.bigcontainers.model.BookingLine;
import io.kellermann.bigcontainers.model.BookingLineType;
import io.kellermann.bigcontainers.model.PackingRequirement;
import io.kellermann.bigcontainers.model.PackingRequirementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingReservationCalculatorTests {
    private final BookingReservationCalculator calculator = new BookingReservationCalculator();

    @Test
    void containerBookingExpandsNestedDescendantsAndPackingRequirements() {
        UUID organizationId = UUID.randomUUID();
        UUID containerModelId = UUID.randomUUID();
        UUID cableModelId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Asset outer = asset(organizationId, containerModelId, "000000", 1, now);
        Asset inner = asset(organizationId, containerModelId, "000012", 2, now);
        Asset cable = asset(organizationId, cableModelId, "0000ZV", 3, now);
        inner.moveTo(null, outer.getId(), now);
        cable.moveTo(null, inner.getId(), now);
        UUID exactAssetId = UUID.randomUUID();
        BookingLine line = new BookingLine(
                UUID.randomUUID(),
                organizationId,
                UUID.randomUUID(),
                BookingLineType.CONTAINER,
                outer.getId(),
                null,
                BigDecimal.ONE,
                now);
        PackingRequirement exact = new PackingRequirement(
                UUID.randomUUID(),
                organizationId,
                outer.getId(),
                PackingRequirementType.SPECIFIC_ASSET,
                null,
                exactAssetId,
                BigDecimal.ONE,
                0,
                now);
        PackingRequirement flexible = new PackingRequirement(
                UUID.randomUUID(),
                organizationId,
                outer.getId(),
                PackingRequirementType.MODEL_QUANTITY,
                cableModelId,
                null,
                BigDecimal.valueOf(2),
                1,
                now);

        List<BookingClaimCandidate> claims =
                calculator.expand(List.of(line), List.of(outer, inner, cable), List.of(exact, flexible));

        assertThat(claims)
                .filteredOn(claim -> claim.type() == BookingClaimType.ASSET)
                .extracting(BookingClaimCandidate::assetId)
                .containsExactlyInAnyOrder(outer.getId(), inner.getId(), cable.getId(), exactAssetId);
        assertThat(claims)
                .filteredOn(claim -> claim.type() == BookingClaimType.MODEL_CAPACITY)
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.assetModelId()).isEqualTo(cableModelId);
                    assertThat(claim.quantity()).isEqualByComparingTo("2");
                });
    }

    @Test
    void separatelyRequestedConsumableIsOnlyAStockClaim() {
        UUID organizationId = UUID.randomUUID();
        UUID stockId = UUID.randomUUID();
        BookingLine line = new BookingLine(
                UUID.randomUUID(),
                organizationId,
                UUID.randomUUID(),
                BookingLineType.CONSUMABLE,
                null,
                stockId,
                new BigDecimal("1.250"),
                Instant.now());

        assertThat(calculator.expand(List.of(line), List.of(), List.of()))
                .containsExactly(new BookingClaimCandidate(
                        BookingClaimType.CONSUMABLE, null, null, stockId, new BigDecimal("1.250"), line.getId()));
    }

    private static Asset asset(UUID organizationId, UUID modelId, String code, int unitNumber, Instant now) {
        return new Asset(UUID.randomUUID(), organizationId, modelId, code, unitNumber, null, null, now);
    }
}
