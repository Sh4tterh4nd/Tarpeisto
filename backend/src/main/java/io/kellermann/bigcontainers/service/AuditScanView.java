package io.kellermann.bigcontainers.service;

import java.time.Instant;
import java.util.UUID;

public record AuditScanView(
        UUID id,
        UUID assetId,
        String assetCode,
        String outcome,
        Instant scannedAt,
        boolean undone,
        String contextSnapshot) {}
