package io.kellermann.tarpeisto.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestAssetView(
        UUID assetId,
        UUID containerAssetId,
        UUID actualParentContainerAssetId,
        boolean isContainer,
        Map<String, Object> snapshot,
        Map<String, Object> containerSnapshot,
        Instant returnedAt) {}
