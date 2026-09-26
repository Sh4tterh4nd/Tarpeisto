package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.AuditTaskView;
import java.util.List;
import java.util.UUID;

public record AuditTaskResponse(UUID id, UUID containerAssetId, String state, List<UUID> dependsOnTaskIds) {
    static AuditTaskResponse from(AuditTaskView view) {
        return new AuditTaskResponse(view.id(), view.containerAssetId(), view.state(), view.dependsOnTaskIds());
    }
}
