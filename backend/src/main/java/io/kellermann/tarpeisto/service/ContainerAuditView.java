package io.kellermann.tarpeisto.service;

import java.util.List;
import java.util.UUID;

public record ContainerAuditView(
        UUID id,
        UUID taskId,
        UUID batchId,
        UUID containerAssetId,
        String state,
        String completionOutcome,
        List<AuditExpectedRequirementView> expectedRequirements,
        List<AuditScanView> scans,
        List<AuditFindingView> findings,
        List<String> blockingReasons) {}
