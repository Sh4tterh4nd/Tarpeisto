package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.AuditFindingType;
import io.kellermann.tarpeisto.model.FindingResolutionAction;
import io.kellermann.tarpeisto.service.FindingReviewView;
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
