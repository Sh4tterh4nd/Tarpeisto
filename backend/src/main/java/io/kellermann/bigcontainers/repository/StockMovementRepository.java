package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.StockMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access to the immutable {@link StockMovement} ledger. {@code save} is only ever used to append a
 * new row; nothing in this codebase updates or deletes one - the {@code stock_movement} table
 * itself additionally rejects both at the database level (see
 * {@code V6__create_consumable_stock_schema.sql}).
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findAllByOrganizationIdAndConsumableStockBalanceIdOrderByOccurredAtDesc(
            UUID organizationId, UUID consumableStockBalanceId);

    List<StockMovement> findAllByOrganizationIdAndTransferGroupIdOrderByOccurredAtAsc(
            UUID organizationId, UUID transferGroupId);
}
