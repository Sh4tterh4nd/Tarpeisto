package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.AssetPlacementView;
import java.util.List;
import java.util.UUID;

public record AssetPlacementResponse(
        UUID assetId,
        UUID directLocationId,
        UUID parentContainerAssetId,
        long version,
        List<String> effectivePath,
        String effectivePathText) {
    public static AssetPlacementResponse from(AssetPlacementView view) {
        return new AssetPlacementResponse(
                view.assetId(),
                view.directLocationId(),
                view.parentContainerAssetId(),
                view.version(),
                view.effectivePath(),
                view.effectivePathText());
    }
}
