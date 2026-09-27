package io.kellermann.tarpeisto.service;

import java.util.List;
import java.util.UUID;

/** Direct and inherited physical placement of a serialized asset. */
public record AssetPlacementView(
        UUID assetId,
        UUID directLocationId,
        UUID parentContainerAssetId,
        long version,
        List<String> effectivePath,
        String effectivePathText) {}
