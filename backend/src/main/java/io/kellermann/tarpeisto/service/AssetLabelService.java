package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.AssetLabelCalibration;
import io.kellermann.tarpeisto.document.AssetLabelCsv;
import io.kellermann.tarpeisto.document.AssetLabelDocument;
import io.kellermann.tarpeisto.document.AssetLabelEntry;
import io.kellermann.tarpeisto.document.AssetLabelFormat;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
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
            TarpeistoPrincipal principal,
            List<UUID> assetIds,
            AssetLabelFormat format,
            int skipFirstPositions,
            AssetLabelCalibration calibration) {
        if (principal != null) principal.requirePermanent();
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
            TarpeistoPrincipal principal, AssetLabelFormat format, AssetLabelCalibration calibration) {
        if (principal != null) principal.requirePermanent();
        requireAuthenticated(principal);
        try {
            return AssetLabelDocument.calibration(requireFormat(format), calibration);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationFailedException(invalid.getMessage());
        }
    }

    public byte[] ptouchCsv(TarpeistoPrincipal principal, List<UUID> assetIds) {
        if (principal != null) principal.requirePermanent();
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

    private static void requireAuthenticated(TarpeistoPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}
