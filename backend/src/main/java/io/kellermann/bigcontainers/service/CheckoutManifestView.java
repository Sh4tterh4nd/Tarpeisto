package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestView(
        UUID id,
        UUID bookingId,
        BookingStatus bookingStatus,
        Instant checkedOutAt,
        UUID checkedOutByUserId,
        Map<String, Object> bookingSnapshot,
        List<CheckoutManifestAssetView> assets,
        List<CheckoutManifestConsumableView> consumables,
        List<String> overrides,
        List<AuditTaskView> auditTasks,
        UUID auditBatchId) {}
