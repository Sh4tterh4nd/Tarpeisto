package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.MediaObject;
import io.kellermann.bigcontainers.model.MediaPurpose;
import java.time.Instant;
import java.util.UUID;

/** Safe API projection: object keys and original filenames are intentionally never exposed. */
public record MediaView(
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
    static MediaView from(MediaObject media) {
        String root = "/api/v1/media/" + media.getId();
        return new MediaView(
                media.getId(),
                media.getPurpose(),
                media.getContentType(),
                media.getByteSize(),
                media.getCaption(),
                media.getDisplayOrder(),
                media.isPrimaryImage(),
                root,
                root + "/thumbnail",
                media.getCreatedAt(),
                media.getVersion());
    }
}
