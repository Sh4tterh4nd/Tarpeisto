package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.ContainerAuditView;
import java.util.List;
import java.util.UUID;

public record ContainerAuditResponse(
        UUID id,
        UUID taskId,
        UUID batchId,
        UUID containerAssetId,
        String state,
        String completionOutcome,
        List<AuditExpectedRequirementResponse> expectedRequirements,
        List<AuditScanResponse> scans,
        List<AuditFindingResponse> findings,
        List<String> blockingReasons) {
    static ContainerAuditResponse from(ContainerAuditView v) {
        return new ContainerAuditResponse(
                v.id(),
                v.taskId(),
                v.batchId(),
                v.containerAssetId(),
                v.state(),
                v.completionOutcome(),
                v.expectedRequirements().stream()
                        .map(AuditExpectedRequirementResponse::from)
                        .toList(),
                v.scans().stream().map(AuditScanResponse::from).toList(),
                v.findings().stream().map(AuditFindingResponse::from).toList(),
                v.blockingReasons());
    }
}
