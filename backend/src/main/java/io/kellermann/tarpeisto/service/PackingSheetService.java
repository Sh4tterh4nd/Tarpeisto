package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.PackingSheetDocument;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Authorized, server-side generation of printable direct-container packing sheets. */
@Service
public class PackingSheetService {

    private final PackingSheetSnapshotService snapshotService;

    public PackingSheetService(PackingSheetSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public byte[] pdf(TarpeistoPrincipal principal, UUID assetId) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        return PackingSheetDocument.render(snapshotService.snapshot(principal.organizationId(), assetId));
    }
}
