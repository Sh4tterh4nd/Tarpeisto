package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.config.S3Properties;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.StaleMediaVersionException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Asset;
import io.kellermann.tarpeisto.model.AssetModel;
import io.kellermann.tarpeisto.model.MediaObject;
import io.kellermann.tarpeisto.model.MediaPurpose;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.repository.AssetModelRepository;
import io.kellermann.tarpeisto.repository.AssetRepository;
import io.kellermann.tarpeisto.repository.MediaObjectRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.storage.MediaStorage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Authorized image workflows. S3 calls deliberately happen outside database transactions. */
@Service
public class MediaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaService.class);
    private static final int MAX_IMAGE_PIXELS = 20_000_000;
    private static final int THUMBNAIL_EDGE = 512;

    private final MediaObjectRepository mediaObjectRepository;
    private final AssetModelRepository assetModelRepository;
    private final AssetRepository assetRepository;
    private final MediaStorage mediaStorage;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ActivityLogService activityLogService;

    public MediaService(
            MediaObjectRepository mediaObjectRepository,
            AssetModelRepository assetModelRepository,
            AssetRepository assetRepository,
            MediaStorage mediaStorage,
            S3Properties s3Properties,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ActivityLogService activityLogService) {
        this.mediaObjectRepository = mediaObjectRepository;
        this.assetModelRepository = assetModelRepository;
        this.assetRepository = assetRepository;
        this.mediaStorage = mediaStorage;
        this.s3Properties = s3Properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.activityLogService = activityLogService;
    }

    public MediaView uploadModelReference(TarpeistoPrincipal principal, UUID assetModelId, MultipartFile file) {
        requireOwnerOrDeputy(principal);
        requireAssetModel(principal.organizationId(), assetModelId);
        return upload(principal, assetModelId, null, MediaPurpose.MODEL_REFERENCE, null, 0, file);
    }

    public MediaView uploadAssetReference(TarpeistoPrincipal principal, UUID assetId, MultipartFile file) {
        requireOwnerOrDeputy(principal);
        requireAsset(principal.organizationId(), assetId);
        return upload(principal, null, assetId, MediaPurpose.ASSET_REFERENCE, null, 0, file);
    }

    public MediaView uploadContainerLayout(
            TarpeistoPrincipal principal, UUID assetId, String caption, MultipartFile file) {
        requireOwnerOrDeputy(principal);
        requireContainerAsset(principal.organizationId(), assetId);
        return upload(principal, null, assetId, MediaPurpose.CONTAINER_LAYOUT, caption, 0, file);
    }

    public MediaView getModelReference(TarpeistoPrincipal principal, UUID assetModelId) {
        requireAuthenticated(principal);
        requireAssetModel(principal.organizationId(), assetModelId);
        return mediaObjectRepository
                .findByOrganizationIdAndAssetModelIdAndPurposeAndArchivedAtIsNull(
                        principal.organizationId(), assetModelId, MediaPurpose.MODEL_REFERENCE)
                .map(MediaView::from)
                .orElseThrow(() -> new NotFoundException("Reference image not found."));
    }

    public MediaView getAssetReference(TarpeistoPrincipal principal, UUID assetId) {
        requireAuthenticated(principal);
        requireAsset(principal.organizationId(), assetId);
        return mediaObjectRepository
                .findByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNull(
                        principal.organizationId(), assetId, MediaPurpose.ASSET_REFERENCE)
                .map(MediaView::from)
                .orElseThrow(() -> new NotFoundException("Reference image not found."));
    }

    public List<MediaView> listContainerLayouts(TarpeistoPrincipal principal, UUID assetId) {
        requireAuthenticated(principal);
        requireContainerAsset(principal.organizationId(), assetId);
        return mediaObjectRepository
                .findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
                        principal.organizationId(), assetId, MediaPurpose.CONTAINER_LAYOUT)
                .stream()
                .map(MediaView::from)
                .toList();
    }

    public MediaStorage.StoredMedia open(TarpeistoPrincipal principal, UUID mediaId, boolean thumbnail) {
        requireAuthenticated(principal);
        MediaObject media = requireMedia(principal.organizationId(), mediaId);
        if (media.isArchived()) throw new NotFoundException("Image not found.");
        return mediaStorage.get(thumbnail ? media.getThumbnailObjectKey() : media.getObjectKey());
    }

    public List<MediaView> updateLayout(
            TarpeistoPrincipal principal,
            UUID mediaId,
            String caption,
            int displayOrder,
            boolean primaryImage,
            long expectedVersion) {
        requireOwnerOrDeputy(principal);
        return transactionTemplate.execute(status -> {
            MediaObject target = requireMedia(principal.organizationId(), mediaId);
            if (target.getPurpose() != MediaPurpose.CONTAINER_LAYOUT) {
                throw new ValidationFailedException("Only container layout images can be reordered or captioned.");
            }
            if (target.getVersion() != expectedVersion) {
                throw new StaleMediaVersionException();
            }
            List<MediaObject> ordered = new ArrayList<>(
                    mediaObjectRepository
                            .findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
                                    principal.organizationId(), target.getAssetId(), MediaPurpose.CONTAINER_LAYOUT));
            ordered.removeIf(item -> item.getId().equals(target.getId()));
            int clampedOrder = Math.max(0, Math.min(displayOrder, ordered.size()));
            ordered.add(clampedOrder, target);
            for (int index = 0; index < ordered.size(); index++) {
                MediaObject item = ordered.get(index);
                item.updateLayout(
                        item.getId().equals(mediaId) ? caption : item.getCaption(), 10_000 + index, clock.instant());
            }
            mediaObjectRepository.flush();
            for (int index = 0; index < ordered.size(); index++) {
                MediaObject item = ordered.get(index);
                item.updateLayout(item.getCaption(), index, clock.instant());
            }
            if (primaryImage) {
                ordered.forEach(item -> item.setPrimaryImage(item.getId().equals(mediaId), clock.instant()));
            } else {
                target.setPrimaryImage(false, clock.instant());
            }
            mediaObjectRepository.flush();
            activityLogService.record(
                    principal.organizationId(),
                    principal.userId(),
                    "MEDIA_LAYOUT_UPDATED",
                    "MEDIA",
                    target.getId(),
                    java.util.Map.of("displayOrder", clampedOrder));
            return ordered.stream().map(MediaView::from).toList();
        });
    }

    public void delete(TarpeistoPrincipal principal, UUID mediaId) {
        requireOwnerOrDeputy(principal);
        MediaObject deleted = transactionTemplate.execute(status -> {
            MediaObject media = requireMedia(principal.organizationId(), mediaId);
            media.archiveForCleanup(clock.instant());
            activityLogService.record(
                    principal.organizationId(), principal.userId(), "MEDIA_REMOVED", "MEDIA", media.getId(), null);
            return media;
        });
        attemptCleanup(deleted);
    }

    public void retryCleanup(TarpeistoPrincipal principal, UUID mediaId) {
        requireOwnerOrDeputy(principal);
        MediaObject media = requireMedia(principal.organizationId(), mediaId);
        if (!media.isArchived() || !media.isCleanupPending()) {
            throw new ValidationFailedException("This image has no pending cleanup.");
        }
        attemptCleanup(media);
    }

    private MediaView upload(
            TarpeistoPrincipal principal,
            UUID assetModelId,
            UUID assetId,
            MediaPurpose purpose,
            String caption,
            int displayOrder,
            MultipartFile file) {
        if (!s3Properties.enabled()) {
            throw new ValidationFailedException("Media storage is not configured.");
        }
        ValidatedImage image = validateImage(file);
        UUID mediaId = UUID.randomUUID();
        String root = "organizations/" + principal.organizationId() + "/media/" + mediaId + "/";
        String objectKey = root + "original" + image.extension();
        String thumbnailKey = root + "thumbnail.jpg";
        try {
            mediaStorage.put(
                    objectKey, image.contentType(), image.bytes().length, new ByteArrayInputStream(image.bytes()));
            mediaStorage.put(
                    thumbnailKey, "image/jpeg", image.thumbnail().length, new ByteArrayInputStream(image.thumbnail()));
        } catch (RuntimeException failure) {
            deleteQuietly(objectKey);
            deleteQuietly(thumbnailKey);
            throw new ValidationFailedException("Could not store the image.");
        }
        MediaObject[] replaced = new MediaObject[1];
        try {
            MediaObject saved = transactionTemplate.execute(status -> {
                if (purpose == MediaPurpose.CONTAINER_LAYOUT) {
                    assetRepository
                            .findWithLockByIdAndOrganizationId(assetId, principal.organizationId())
                            .orElseThrow(() -> new NotFoundException("Asset not found."));
                }
                MediaObject previous = findSingle(principal.organizationId(), assetModelId, assetId, purpose);
                replaced[0] = previous;
                MediaObject media = new MediaObject(
                        mediaId,
                        principal.organizationId(),
                        assetModelId,
                        assetId,
                        purpose,
                        objectKey,
                        thumbnailKey,
                        image.contentType(),
                        image.bytes().length,
                        image.sha256(),
                        caption,
                        purpose == MediaPurpose.CONTAINER_LAYOUT
                                ? mediaObjectRepository
                                        .findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
                                                principal.organizationId(), assetId, purpose)
                                        .size()
                                : displayOrder,
                        purpose == MediaPurpose.CONTAINER_LAYOUT
                                && mediaObjectRepository
                                        .findAllByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNullOrderByDisplayOrderAsc(
                                                principal.organizationId(), assetId, purpose)
                                        .isEmpty(),
                        principal.userId(),
                        clock.instant());
                if (previous != null) {
                    previous.archiveForCleanup(clock.instant());
                    // The partial unique index permits the replacement only after old metadata
                    // is flushed away; both operations remain rollback-safe in this transaction.
                    mediaObjectRepository.flush();
                }
                MediaObject persisted = mediaObjectRepository.save(media);
                activityLogService.record(
                        principal.organizationId(),
                        principal.userId(),
                        previous == null ? "MEDIA_UPLOADED" : "MEDIA_REPLACED",
                        "MEDIA",
                        persisted.getId(),
                        java.util.Map.of("purpose", purpose.name()));
                return persisted;
            });
            if (replaced[0] != null) {
                attemptCleanup(replaced[0]);
            }
            return MediaView.from(saved);
        } catch (RuntimeException failure) {
            deleteQuietly(objectKey);
            deleteQuietly(thumbnailKey);
            throw failure;
        }
    }

    private MediaObject findSingle(UUID organizationId, UUID assetModelId, UUID assetId, MediaPurpose purpose) {
        if (purpose == MediaPurpose.MODEL_REFERENCE) {
            return mediaObjectRepository
                    .findByOrganizationIdAndAssetModelIdAndPurposeAndArchivedAtIsNull(
                            organizationId, assetModelId, purpose)
                    .orElse(null);
        }
        if (purpose == MediaPurpose.ASSET_REFERENCE) {
            return mediaObjectRepository
                    .findByOrganizationIdAndAssetIdAndPurposeAndArchivedAtIsNull(organizationId, assetId, purpose)
                    .orElse(null);
        }
        return null;
    }

    private ValidatedImage validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ValidationFailedException("Choose an image to upload.");
        if (file.getSize() > s3Properties.maxUploadBytes()) {
            throw new ValidationFailedException("Image exceeds the configured upload limit.");
        }
        try {
            byte[] bytes = file.getBytes();
            String contentType = detectImageType(bytes);
            requireSafeDimensions(bytes);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) throw new ValidationFailedException("The uploaded file is not a readable image.");
            return new ValidatedImage(
                    bytes,
                    contentType,
                    thumbnail(decoded),
                    sha256(bytes),
                    contentType.equals("image/png") ? ".png" : ".jpg");
        } catch (IOException failure) {
            throw new ValidationFailedException("The uploaded file is not a readable image.");
        }
    }

    private static String detectImageType(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff)
            return "image/jpeg";
        throw new ValidationFailedException("Only PNG and JPEG images are supported.");
    }

    private static void requireSafeDimensions(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ValidationFailedException("The uploaded file is not a readable image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new ValidationFailedException("Image dimensions are not allowed.");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private void attemptCleanup(MediaObject media) {
        try {
            mediaStorage.delete(media.getObjectKey());
            mediaStorage.delete(media.getThumbnailObjectKey());
            transactionTemplate.executeWithoutResult(status -> mediaObjectRepository
                    .findByIdAndOrganizationId(media.getId(), media.getOrganizationId())
                    .ifPresent(current -> current.markCleanupCompleted(clock.instant())));
        } catch (RuntimeException failure) {
            LOGGER.warn("Media cleanup remains pending for {}", media.getId(), failure);
        }
    }

    private static byte[] thumbnail(BufferedImage source) throws IOException {
        double scale = Math.min(1d, (double) THUMBNAIL_EDGE / Math.max(source.getWidth(), source.getHeight()));
        BufferedImage thumbnail = new BufferedImage(
                Math.max(1, (int) Math.round(source.getWidth() * scale)),
                Math.max(1, (int) Math.round(source.getHeight() * scale)),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(thumbnail, "jpeg", output)) throw new IOException("JPEG writer unavailable");
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private MediaObject requireMedia(UUID organizationId, UUID mediaId) {
        return mediaObjectRepository
                .findByIdAndOrganizationId(mediaId, organizationId)
                .orElseThrow(() -> new NotFoundException("Image not found."));
    }

    private AssetModel requireAssetModel(UUID organizationId, UUID assetModelId) {
        return assetModelRepository
                .findByIdAndOrganizationId(assetModelId, organizationId)
                .orElseThrow(() -> new NotFoundException("Asset model not found."));
    }

    private Asset requireAsset(UUID organizationId, UUID assetId) {
        return assetRepository
                .findByIdAndOrganizationId(assetId, organizationId)
                .orElseThrow(() -> new NotFoundException("Asset not found."));
    }

    private void requireContainerAsset(UUID organizationId, UUID assetId) {
        Asset asset = requireAsset(organizationId, assetId);
        if (!requireAssetModel(organizationId, asset.getAssetModelId()).isCanContainAssets()) {
            throw new ValidationFailedException("Layout images can only be added to a container asset.");
        }
    }

    private void deleteQuietly(String objectKey) {
        try {
            mediaStorage.delete(objectKey);
        } catch (RuntimeException ignored) {
        }
    }

    private void requireOwnerOrDeputy(TarpeistoPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)) {
            throw new AccessDeniedException("Owner or Deputy role required.");
        }
    }

    private void requireAuthenticated(TarpeistoPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Authentication required.");
    }

    private record ValidatedImage(
            byte[] bytes, String contentType, byte[] thumbnail, String sha256, String extension) {}
}
