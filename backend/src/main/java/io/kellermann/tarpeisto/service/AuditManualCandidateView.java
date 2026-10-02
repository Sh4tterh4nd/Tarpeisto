package io.kellermann.tarpeisto.service;

import java.util.UUID;

public record AuditManualCandidateView(
        UUID id, String displayName, String publicCode, UUID assetModelId, String modelName, boolean active) {}
