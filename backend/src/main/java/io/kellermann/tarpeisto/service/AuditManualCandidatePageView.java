package io.kellermann.tarpeisto.service;

import java.util.List;

public record AuditManualCandidatePageView(List<AuditManualCandidateView> items, String nextCursor) {}
