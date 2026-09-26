package io.kellermann.bigcontainers.service;

import java.util.UUID;

public record AuditFindingView(UUID id, UUID assetId, String type, String note, String detail) {}
