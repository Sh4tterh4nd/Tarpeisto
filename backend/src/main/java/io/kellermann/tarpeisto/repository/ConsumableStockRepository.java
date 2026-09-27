package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.ConsumableStock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read-side access to {@link ConsumableStock} balances. Every quantity mutation instead goes
 * through {@code ConsumableStockLedgerRepository}'s raw SQL - see {@link ConsumableStock}'s Javadoc
 * for why this repository is never used to read a balance inside the same transaction as one of
 * those mutations.
 */
public interface ConsumableStockRepository extends JpaRepository<ConsumableStock, UUID> {

    Optional<ConsumableStock> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ConsumableStock> findAllByOrganizationIdAndAssetModelIdOrderByCreatedAtAsc(
            UUID organizationId, UUID assetModelId);

    List<ConsumableStock> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    boolean existsByOrganizationIdAndAssetModelId(UUID organizationId, UUID assetModelId);

    Optional<ConsumableStock> findByOrganizationIdAndAssetModelIdAndContainerAssetId(
            UUID organizationId, UUID assetModelId, UUID containerAssetId);

    List<ConsumableStock> findAllByOrganizationIdAndContainerAssetIdOrderByCreatedAtAsc(
            UUID organizationId, UUID containerAssetId);

    List<ConsumableStock> findAllByOrganizationIdAndLocationIdOrderByCreatedAtAsc(UUID organizationId, UUID locationId);

    boolean existsByOrganizationIdAndLocationIdAndQuantityGreaterThan(
            UUID organizationId, UUID locationId, BigDecimal quantity);

    boolean existsByOrganizationIdAndContainerAssetIdAndQuantityGreaterThan(
            UUID organizationId, UUID containerAssetId, BigDecimal quantity);
}
