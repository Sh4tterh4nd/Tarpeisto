package io.kellermann.tarpeisto.service;

import java.util.UUID;

public record AuditFindingView(
        UUID id,
        UUID sourceOperationId,
        UUID assetId,
        String type,
        String note,
        String detail,
        UUID recordedByUserId,
        String recordedByDisplayName) {
    public AuditFindingView(UUID id, UUID sourceOperationId, UUID assetId, String type, String note, String detail) {
        this(id, sourceOperationId, assetId, type, note, detail, null, null);
    }
}
