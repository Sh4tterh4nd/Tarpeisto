package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.MediaPurpose;
import io.kellermann.bigcontainers.service.MediaView;
import java.time.Instant;
import java.util.UUID;

/** Safe media metadata response; opaque object-storage keys are never serialized. */
public record MediaResponse(
        UUID id,
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
