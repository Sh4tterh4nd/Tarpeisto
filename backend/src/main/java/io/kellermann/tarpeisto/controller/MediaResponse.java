package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.MediaPurpose;
import io.kellermann.tarpeisto.service.MediaView;
import java.time.Instant;
import java.util.UUID;

/** Safe media metadata response; opaque object-storage keys are never serialized. */
public record MediaResponse(
        UUID id,
        UUID auditId,
        UUID findingId,
        UUID uploadOperationId,
        MediaPurpose purpose,
        String contentType,
        long byteSize,
        String caption,
        int displayOrder,
        boolean primaryImage,
        String imageUrl,
        String thumbnailUrl,
        Instant createdAt,
        long version) {
    static MediaResponse from(MediaView view) {
        return new MediaResponse(
                view.id(),
                view.auditId(),
                view.findingId(),
                view.uploadOperationId(),
                view.purpose(),
                view.contentType(),
                view.byteSize(),
                view.caption(),
                view.displayOrder(),
                view.primaryImage(),
                view.imageUrl(),
                view.thumbnailUrl(),
                view.createdAt(),
                view.version());
    }
}
