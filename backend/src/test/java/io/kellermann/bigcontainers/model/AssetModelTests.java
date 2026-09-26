package io.kellermann.bigcontainers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit test: no Spring context. Covers the specification section 6.2 cross-field invariants. */
class AssetModelTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void constructorAcceptsASerializedContainerCapableModelWithNoStockFields() {
        AssetModel model = serializedModel(true);
        assertThat(model.getTrackingMode()).isEqualTo(TrackingMode.SERIALIZED_ASSET);
        assertThat(model.isCanContainAssets()).isTrue();
        assertThat(model.getStockUnitLabel()).isNull();
        assertThat(model.getLowStockThreshold()).isNull();
    }

    @Test
    void constructorAcceptsAQuantityStockModelWithAStockUnitLabel() {
        AssetModel model = quantityModel("roll", new BigDecimal("5.000"));
        assertThat(model.getTrackingMode()).isEqualTo(TrackingMode.QUANTITY_STOCK);
        assertThat(model.getStockUnitLabel()).isEqualTo("roll");
        assertThat(model.isCanContainAssets()).isFalse();
    }

    @Test
    void constructorRejectsAQuantityStockModelThatIsContainerCapable() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Gaffer tape",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.QUANTITY_STOCK,
                        "roll",
                        null,
                        true,
                        NOW));
    }

    @Test
    void constructorRejectsAQuantityStockModelWithoutAStockUnitLabel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Gaffer tape",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.QUANTITY_STOCK,
                        null,
                        null,
                        false,
                        NOW));
    }

    @Test
    void constructorRejectsAQuantityStockModelWithANegativeLowStockThreshold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Gaffer tape",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.QUANTITY_STOCK,
                        "roll",
                        new BigDecimal("-1"),
                        false,
                        NOW));
    }

    @Test
    void constructorRejectsASerializedModelWithAStockUnitLabel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "UniFi AP-HD",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.SERIALIZED_ASSET,
                        "roll",
                        null,
                        false,
                        NOW));
    }

    @Test
    void constructorRejectsASerializedModelWithALowStockThreshold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "UniFi AP-HD",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.SERIALIZED_ASSET,
                        null,
                        BigDecimal.TEN,
                        false,
                        NOW));
    }

    @Test
    void constructorRejectsABlankName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AssetModel(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        " ",
                        null,
                        UUID.randomUUID(),
                        null,
                        TrackingMode.SERIALIZED_ASSET,
                        null,
                        null,
                        false,
                        NOW));
    }

    @Test
    void setCanContainAssetsRejectsEnablingOnAQuantityStockModel() {
        AssetModel model = quantityModel("roll", null);
        assertThatIllegalArgumentException().isThrownBy(() -> model.setCanContainAssets(true, NOW.plusSeconds(1)));
    }

    @Test
    void setCanContainAssetsAllowsEnablingOnASerializedModel() {
        AssetModel model = serializedModel(false);
        model.setCanContainAssets(true, NOW.plusSeconds(1));
        assertThat(model.isCanContainAssets()).isTrue();
    }

    @Test
    void changeTrackingModeToQuantityStockForcesCanContainAssetsOff() {
        AssetModel model = serializedModel(true);
        model.changeTrackingMode(TrackingMode.QUANTITY_STOCK, "roll", null, NOW.plusSeconds(1));
        assertThat(model.isCanContainAssets()).isFalse();
        assertThat(model.getStockUnitLabel()).isEqualTo("roll");
    }

    @Test
    void changeTrackingModeToSerializedClearsStockFields() {
        AssetModel model = quantityModel("roll", new BigDecimal("2.000"));
        model.changeTrackingMode(TrackingMode.SERIALIZED_ASSET, null, null, NOW.plusSeconds(1));
        assertThat(model.getStockUnitLabel()).isNull();
        assertThat(model.getLowStockThreshold()).isNull();
    }

    @Test
    void changeTrackingModeToQuantityStockWithoutAStockUnitLabelIsRejected() {
        AssetModel model = serializedModel(false);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> model.changeTrackingMode(TrackingMode.QUANTITY_STOCK, null, null, NOW.plusSeconds(1)));
    }

    @Test
    void isQuantityTrackedReflectsTrackingMode() {
        assertThat(serializedModel(false).isQuantityTracked()).isFalse();
        assertThat(quantityModel("roll", null).isQuantityTracked()).isTrue();
    }

    @Test
    void archiveAndRestoreToggleArchivedState() {
        AssetModel model = serializedModel(false);
        model.archive(NOW.plusSeconds(1));
        assertThat(model.isArchived()).isTrue();
        model.restore(NOW.plusSeconds(2));
        assertThat(model.isArchived()).isFalse();
    }

    private static AssetModel serializedModel(boolean canContainAssets) {
        return new AssetModel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "UniFi AP-HD",
                "Access point",
                UUID.randomUUID(),
                "https://example.com/ap",
                TrackingMode.SERIALIZED_ASSET,
                null,
                null,
                canContainAssets,
                NOW);
    }

    private static AssetModel quantityModel(String stockUnitLabel, BigDecimal lowStockThreshold) {
        return new AssetModel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Gaffer tape 50 mm",
                null,
                UUID.randomUUID(),
                null,
                TrackingMode.QUANTITY_STOCK,
                stockUnitLabel,
                lowStockThreshold,
                false,
                NOW);
    }
}
