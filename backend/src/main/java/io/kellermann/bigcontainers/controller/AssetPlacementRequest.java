package io.kellermann.bigcontainers.controller;

import java.util.UUID;

public record AssetPlacementRequest(UUID locationId, UUID parentContainerAssetId, long expectedVersion) {}
