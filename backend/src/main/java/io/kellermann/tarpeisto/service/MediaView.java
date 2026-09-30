package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.MediaObject;
import io.kellermann.tarpeisto.model.MediaPurpose;
import java.time.Instant;
import java.util.UUID;

/** Safe API projection: object keys and original filenames are intentionally never exposed. */
public record MediaView(
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
    static MediaView from(MediaObject media) {
        String root = "/api/v1/media/" + media.getId();
        return new MediaView(
                media.getId(),
                media.getAuditId(),
                media.getFindingId(),
                media.getUploadOperationId(),
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
