package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.document.AssetLabelCalibration;
import io.kellermann.bigcontainers.document.AssetLabelCsv;
import io.kellermann.bigcontainers.document.AssetLabelDocument;
import io.kellermann.bigcontainers.document.AssetLabelEntry;
import io.kellermann.bigcontainers.document.AssetLabelFormat;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Organization-scoped label artifacts. Rendering deliberately happens after all repository reads. */
@Service
public class AssetLabelService {

    private final AssetLabelSnapshotService snapshotService;

    public AssetLabelService(AssetLabelSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public byte[] labels(
            BigContainersPrincipal principal,
            List<UUID> assetIds,
            AssetLabelFormat format,
            int skipFirstPositions,
            AssetLabelCalibration calibration) {
        requireAuthenticated(principal);
        AssetLabelFormat resolvedFormat = requireFormat(format);
        validateSkip(skipFirstPositions, resolvedFormat);
        List<AssetLabelEntry> entries = snapshotService.snapshot(principal.organizationId(), assetIds);
        try {
            return AssetLabelDocument.labels(entries, resolvedFormat, skipFirstPositions, calibration);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
    }

    public byte[] calibration(
            BigContainersPrincipal principal, AssetLabelFormat format, AssetLabelCalibration calibration) {
        requireAuthenticated(principal);
        try {
            return AssetLabelDocument.calibration(requireFormat(format), calibration);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
    }

    public byte[] ptouchCsv(BigContainersPrincipal principal, List<UUID> assetIds) {
        requireAuthenticated(principal);
        List<AssetLabelEntry> entries = snapshotService.snapshot(principal.organizationId(), assetIds);
        return AssetLabelCsv.write(entries);
    }

    private static AssetLabelFormat requireFormat(AssetLabelFormat format) {
        if (format == null) {
            throw new ValidationFailedException("A label format is required.");
        }
        return format;
    }

    private static void validateSkip(int skipFirstPositions, AssetLabelFormat format) {
        if (skipFirstPositions < 0 || skipFirstPositions >= format.labelsPerPage()) {
            throw new ValidationFailedException(
                    "skipFirstPositions must be between 0 and " + (format.labelsPerPage() - 1) + ".");
        }
    }

    private static void requireAuthenticated(BigContainersPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}
