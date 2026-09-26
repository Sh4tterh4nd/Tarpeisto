package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.AuditFindingView;
import java.util.UUID;

public record AuditFindingResponse(UUID id, UUID assetId, String type, String note, String detail) {
    static AuditFindingResponse from(AuditFindingView v) {
        return new AuditFindingResponse(v.id(), v.assetId(), v.type(), v.note(), v.detail());
    }
}
