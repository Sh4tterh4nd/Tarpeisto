package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AuditManualCandidatePageView;
import java.util.List;

public record AuditManualCandidatePageResponse(List<AuditManualCandidateResponse> items, String nextCursor) {
    static AuditManualCandidatePageResponse from(AuditManualCandidatePageView v) {
        return new AuditManualCandidatePageResponse(
                v.items().stream().map(AuditManualCandidateResponse::from).toList(), v.nextCursor());
    }
}
