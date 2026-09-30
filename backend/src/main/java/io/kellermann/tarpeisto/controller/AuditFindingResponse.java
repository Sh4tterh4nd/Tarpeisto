package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AuditFindingView;
import java.util.UUID;

public record AuditFindingResponse(
        UUID id, UUID sourceOperationId, UUID assetId, String type, String note, String detail) {
    static AuditFindingResponse from(AuditFindingView v) {
        return new AuditFindingResponse(v.id(), v.sourceOperationId(), v.assetId(), v.type(), v.note(), v.detail());
    }
}
