package io.kellermann.tarpeisto.controller;

import java.util.UUID;

public record AssetPlacementRequest(UUID locationId, UUID parentContainerAssetId, long expectedVersion) {}
