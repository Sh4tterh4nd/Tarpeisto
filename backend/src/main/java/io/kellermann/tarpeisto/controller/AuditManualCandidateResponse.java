package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AuditManualCandidateView;
import java.util.UUID;

public record AuditManualCandidateResponse(
        UUID id, String displayName, String publicCode, UUID assetModelId, String modelName, boolean active) {
    static AuditManualCandidateResponse from(AuditManualCandidateView v) {
        return new AuditManualCandidateResponse(
                v.id(), v.displayName(), v.publicCode(), v.assetModelId(), v.modelName(), v.active());
    }
}
