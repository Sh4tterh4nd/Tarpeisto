package io.kellermann.bigcontainers.service;

import java.util.List;
import java.util.UUID;

public record AuditTaskView(UUID id, UUID containerAssetId, String state, List<UUID> dependsOnTaskIds) {}
