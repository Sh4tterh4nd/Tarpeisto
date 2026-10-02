package io.kellermann.tarpeisto.service;

import java.time.Instant;
import java.util.UUID;

public record AuditScanView(
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
    public AuditScanView(
            UUID id,
            UUID operationId,
            UUID assetId,
            String assetCode,
            String outcome,
            Instant scannedAt,
            boolean undone,
            String contextSnapshot,
            UUID recordedByUserId,
            String recordedByDisplayName) {
        this(
                id,
                operationId,
                assetId,
                assetCode,
                outcome,
                scannedAt,
                undone,
                contextSnapshot,
                recordedByUserId,
                recordedByDisplayName,
                null);
    }

    public AuditScanView(
            UUID id,
            UUID operationId,
            UUID assetId,
            String assetCode,
            String outcome,
            Instant scannedAt,
            boolean undone,
            String contextSnapshot) {
        this(id, operationId, assetId, assetCode, outcome, scannedAt, undone, contextSnapshot, null, null, null);
    }
}
