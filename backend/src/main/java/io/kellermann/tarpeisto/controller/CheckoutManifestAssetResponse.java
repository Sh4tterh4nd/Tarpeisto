package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.CheckoutManifestAssetView;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestAssetResponse(
        UUID assetId,
        UUID containerAssetId,
        UUID actualParentContainerAssetId,
        boolean isContainer,
        Map<String, Object> snapshot,
        Map<String, Object> containerSnapshot,
        Instant returnedAt) {
    static CheckoutManifestAssetResponse from(CheckoutManifestAssetView view) {
        return new CheckoutManifestAssetResponse(
                view.assetId(),
                view.containerAssetId(),
                view.actualParentContainerAssetId(),
                view.isContainer(),
                view.snapshot(),
                view.containerSnapshot(),
                view.returnedAt());
    }
}
