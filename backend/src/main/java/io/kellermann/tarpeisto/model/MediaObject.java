package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted, organization-scoped metadata for an S3 object; image bytes never enter PostgreSQL. */
@Entity
@Table(name = "media_object")
public class MediaObject {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_model_id")
    private UUID assetModelId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "container_audit_id", updatable = false)
    private UUID auditId;

    @Column(name = "audit_finding_id", updatable = false)
    private UUID findingId;

    @Column(name = "upload_operation_id", updatable = false)
    private UUID uploadOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private MediaPurpose purpose;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "thumbnail_object_key", nullable = false, updatable = false, length = 512)
    private String thumbnailObjectKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private long byteSize;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "caption", length = 500)
    private String caption;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "primary_image", nullable = false)
    private boolean primaryImage;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "cleanup_pending", nullable = false)
    private boolean cleanupPending;

    @Column(name = "cleanup_completed_at")
    private Instant cleanupCompletedAt;

    @Column(name = "uploader_user_id", nullable = false, updatable = false)
    private UUID uploaderUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected MediaObject() {}

    public MediaObject(
            UUID id,
            UUID organizationId,
            UUID assetModelId,
            UUID assetId,
            MediaPurpose purpose,
            String objectKey,
            String thumbnailObjectKey,
            String contentType,
            long byteSize,
            String sha256,
            String caption,
            int displayOrder,
            boolean primaryImage,
            UUID uploaderUserId,
            Instant now) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.assetModelId = assetModelId;
        this.assetId = assetId;
        this.purpose = Objects.requireNonNull(purpose);
        this.objectKey = Objects.requireNonNull(objectKey);
        this.thumbnailObjectKey = Objects.requireNonNull(thumbnailObjectKey);
        this.contentType = Objects.requireNonNull(contentType);
        this.byteSize = byteSize;
        this.sha256 = Objects.requireNonNull(sha256);
        this.caption = normalizeCaption(caption);
        this.displayOrder = displayOrder;
        this.primaryImage = primaryImage;
        this.uploaderUserId = Objects.requireNonNull(uploaderUserId);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public MediaObject associateEvidence(UUID auditId, UUID findingId, UUID operationId) {
        if (purpose != MediaPurpose.AUDIT_EVIDENCE) throw new IllegalStateException("Evidence purpose required.");
        this.auditId = Objects.requireNonNull(auditId);
        this.findingId = Objects.requireNonNull(findingId);
        this.uploadOperationId = Objects.requireNonNull(operationId);
        return this;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public UUID getFindingId() {
        return findingId;
    }

    public UUID getUploadOperationId() {
        return uploadOperationId;
    }

    public void updateLayout(String caption, int displayOrder, Instant now) {
        if (purpose != MediaPurpose.CONTAINER_LAYOUT)
            throw new IllegalStateException("Only layout images have captions or order.");
        this.caption = normalizeCaption(caption);
        this.displayOrder = displayOrder;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void setPrimaryImage(boolean primaryImage, Instant now) {
        if (purpose != MediaPurpose.CONTAINER_LAYOUT)
            throw new IllegalStateException("Only layout images may be primary.");
        this.primaryImage = primaryImage;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public void archiveForCleanup(Instant now) {
        if (purpose == MediaPurpose.AUDIT_EVIDENCE) throw new IllegalStateException("Audit evidence is immutable.");
        this.archivedAt = Objects.requireNonNull(now);
        this.cleanupPending = true;
        this.updatedAt = now;
    }

    public void markCleanupCompleted(Instant now) {
        this.cleanupPending = false;
        this.cleanupCompletedAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    private static String normalizeCaption(String caption) {
        if (caption == null || caption.isBlank()) return null;
        String value = caption.trim();
        if (value.length() > 500) throw new IllegalArgumentException("Image caption must not exceed 500 characters.");
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAssetModelId() {
        return assetModelId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public MediaPurpose getPurpose() {
        return purpose;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getThumbnailObjectKey() {
        return thumbnailObjectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public String getCaption() {
        return caption;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isPrimaryImage() {
        return primaryImage;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isCleanupPending() {
        return cleanupPending;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
