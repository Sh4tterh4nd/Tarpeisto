package io.kellermann.tarpeisto.service;

import java.util.List;
import java.util.UUID;

public record AssignedAuditTaskView(
        UUID taskId,
        UUID batchId,
        UUID containerAssetId,
        String displayName,
        String publicCode,
        String state,
        List<String> blockingReasons) {}
