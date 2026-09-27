package io.kellermann.tarpeisto.service;

import java.util.UUID;

/**
 * Checks whether a candidate public asset code already exists for an organization
 * (specification section 9.3: "A database unique constraint covers {@code
 * (organization_id, public_code)}"). {@link AssetCodeGenerationService} depends on this
 * interface rather than a repository directly: Phase 2a's {@code physical_asset} table does not
 * exist yet (it is Phase 2b), so the generation library stays a clean, injectable domain service
 * with no dependency on a table that is not there. Once Phase 2b adds {@code physical_asset}, its
 * repository implements this interface (for example {@code physicalAssetRepository::existsByOrganizationIdAndPublicCode}).
 */
@FunctionalInterface
public interface AssetCodeUniquenessChecker {

    boolean exists(UUID organizationId, String publicCode);
}
