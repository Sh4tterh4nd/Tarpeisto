package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AuditScanView;
import java.time.Instant;
import java.util.UUID;

public record AuditScanResponse(
        UUID id,
        UUID operationId,
        UUID assetId,
        String assetCode,
        String outcome,
        Instant scannedAt,
        boolean undone,
        String contextSnapshot,
        UUID recordedByUserId,
        String recordedByDisplayName,
        UUID assetModelId) {
    static AuditScanResponse from(AuditScanView v) {
        return new AuditScanResponse(
                v.id(),
                v.operationId(),
                v.assetId(),
                v.assetCode(),
                v.outcome(),
                v.scannedAt(),
                v.undone(),
                v.contextSnapshot(),
                v.recordedByUserId(),
                v.recordedByDisplayName(),
                v.assetModelId());
    }
}
