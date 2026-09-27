package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.BookingStatus;
import io.kellermann.tarpeisto.service.CheckoutManifestView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestResponse(
        UUID id,
        UUID bookingId,
        BookingStatus bookingStatus,
        Instant checkedOutAt,
        UUID checkedOutByUserId,
        Map<String, Object> bookingSnapshot,
        List<CheckoutManifestAssetResponse> assets,
        List<CheckoutManifestConsumableResponse> consumables,
        List<String> overrides,
        List<AuditTaskResponse> auditTasks,
        UUID auditBatchId) {
    public static CheckoutManifestResponse from(CheckoutManifestView view) {
        return new CheckoutManifestResponse(
                view.id(),
                view.bookingId(),
                view.bookingStatus(),
                view.checkedOutAt(),
                view.checkedOutByUserId(),
                view.bookingSnapshot(),
                view.assets().stream().map(CheckoutManifestAssetResponse::from).toList(),
                view.consumables().stream()
                        .map(CheckoutManifestConsumableResponse::from)
                        .toList(),
                view.overrides(),
                view.auditTasks().stream().map(AuditTaskResponse::from).toList(),
                view.auditBatchId());
    }
}
