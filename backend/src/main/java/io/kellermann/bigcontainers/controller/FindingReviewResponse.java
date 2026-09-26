package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.AuditFindingType;
import io.kellermann.bigcontainers.model.FindingResolutionAction;
import io.kellermann.bigcontainers.service.FindingReviewView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FindingReviewResponse(
        UUID id,
        UUID auditId,
        UUID assetId,
        AuditFindingType type,
        String note,
        String detail,
        Instant recordedAt,
        boolean resolved,
        FindingResolutionAction resolutionAction,
        Instant resolvedAt,
        List<FindingResolutionAction> applicableActions,
        UUID auditTaskId,
        UUID containerAssetId) {
    static FindingReviewResponse from(FindingReviewView view) {
        return new FindingReviewResponse(
                view.id(),
                view.auditId(),
                view.assetId(),
                view.type(),
                view.note(),
                view.detail(),
                view.recordedAt(),
                view.resolved(),
                view.resolutionAction(),
                view.resolvedAt(),
                view.applicableActions(),
                view.auditTaskId(),
                view.containerAssetId());
    }
}
