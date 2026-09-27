package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.document.PackingSheetDocument;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
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

    public byte[] pdf(BigContainersPrincipal principal, UUID assetId) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        return PackingSheetDocument.render(snapshotService.snapshot(principal.organizationId(), assetId));
    }
}
