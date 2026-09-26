package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.AuditFindingType;
import io.kellermann.bigcontainers.model.FindingResolutionAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FindingReviewView(
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
        UUID containerAssetId) {}
