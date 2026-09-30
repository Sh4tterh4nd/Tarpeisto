package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Applies every {@code ConsumableStock} quantity change with genuine transactional row locking
 * against PostgreSQL, using raw SQL rather than Spring Data JPA per
 * docs/DEVELOPMENT_POLICIES.md section 5.2 ("Spring {@code JdbcClient} handles ... bulk operations
 * and SQL clearer than ORM expressions") - the same idiom {@code AssetUnitNumberSequenceRepository}
 * already establishes for {@code asset_model.next_unit_number}.
 *
 * <p><strong>Two deliberately different locking strategies, chosen per shape of the problem:</strong>
 *
 * <ul>
 *   <li><strong>A single balance</strong> (receipt, issue, return, consumption, adjustment): {@link
 *       #applyDeltaToContainerBalance} issues one atomic {@code UPDATE ... WHERE quantity + :delta
 *       >= 0 RETURNING quantity}. PostgreSQL takes the row-level lock and evaluates the guard
 *       condition in the same statement, so there is no window between "read the current quantity"
 *       and "write the new one" for a second transaction to slip into - the classic
 *       read-then-write race that would let two concurrent issues both see enough stock and both
 *       succeed. A concurrent competing statement against the same row simply blocks until the
 *       first commits or rolls back, then re-evaluates the guard against the now-current value.
 *       This needs no explicit {@code SELECT ... FOR UPDATE} and holds under the default {@code
 *       READ COMMITTED} isolation level, exactly like {@code AssetUnitNumberSequenceRepository}'s
 *       allocation. This is the path the mandatory concurrent-issue test exercises.
 *   <li><strong>A transfer's two balances</strong> (source and destination): a single atomic
 *       {@code UPDATE} cannot express "decrement this row and increment that other row as one
 *       guarded unit," so {@link #lockContainerBalancesForTransfer} takes explicit {@code SELECT
 *       ... FOR UPDATE} locks on both rows before the caller computes and applies either side's new
 *       quantity. <strong>Lock ordering:</strong> the two rows are always locked in ascending order
 *       of their {@code container_asset_id}, regardless of which one is logically the source or the
 *       destination. Two transfers that move stock in opposite directions between the same pair of
 *       stock places (A to B, and B to A) would otherwise be able to lock in opposite orders and
 *       deadlock; locking by a value that does not depend on transfer direction (the place
 *       identifiers themselves) makes every transaction that ever touches this pair of rows agree
 *       on the same global order.
 * </ul>
 *
 * <p>Every "ensure the balance row exists" step below is a plain {@code INSERT ... ON CONFLICT
 * (...) DO NOTHING}, racing safely against a concurrent first receipt into a brand-new stock place:
 * whichever transaction's insert wins, the other's is a harmless no-op, and both then proceed to
 * the row-locked mutation above.
 */
@Repository
public class ConsumableStockLedgerRepository {

    private final JdbcClient jdbcClient;

    public ConsumableStockLedgerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** One balance row's identity, quantity, and timestamps, as read or locked by this repository. */
    public record BalanceState(
            UUID balanceId, BigDecimal quantity, Instant createdAt, Instant updatedAt, long version) {
        public BalanceState after(BigDecimal quantity, Instant now) {
            return new BalanceState(balanceId, quantity, createdAt, now, version + 1);
        }
    }

    /** Both sides of a transfer, locked in the canonical order documented on the class Javadoc. */
    public record TransferLock(BalanceState source, BalanceState destination) {}

    /** A closed, validated stock-place choice used by Phase 4's location-aware commands. */
    public record StockPlace(UUID containerAssetId, UUID locationId) {
        public StockPlace {
            if ((containerAssetId == null) == (locationId == null)) {
                throw new IllegalArgumentException("A stock place must be exactly one container or location.");
            }
        }

        public static StockPlace container(UUID id) {
            return new StockPlace(id, null);
        }

        public static StockPlace location(UUID id) {
            return new StockPlace(null, id);
        }

        public UUID id() {
            return containerAssetId != null ? containerAssetId : locationId;
        }

        public String kind() {
            return containerAssetId != null ? "container" : "location";
        }
    }

    public Optional<BalanceState> applyDeltaToPlace(
            UUID organizationId, UUID assetModelId, StockPlace place, BigDecimal delta, Instant now) {
        if (place.containerAssetId() != null) {
            return applyDeltaToContainerBalance(organizationId, assetModelId, place.containerAssetId(), delta, now);
        }
        if (delta.signum() > 0) ensureLocationBalanceExists(organizationId, assetModelId, place.locationId(), now);
        requireActivePlace(organizationId, assetModelId, place);
        return jdbcClient
                .sql("""
                UPDATE consumable_stock_balance SET quantity=quantity + :delta, updated_at=:now, version=version+1
                WHERE organization_id=:organizationId AND asset_model_id=:assetModelId AND location_id=:locationId
                  AND archived_at IS NULL AND quantity + :delta >= 0 RETURNING id, quantity, created_at, updated_at, version
                """)
                .param("delta", delta)
                .param("now", toOffsetDateTime(now))
                .param("organizationId", organizationId)
                .param("assetModelId", assetModelId)
                .param("locationId", place.locationId())
                .query(ConsumableStockLedgerRepository::mapBalanceState)
                .optional();
    }

    public TransferLock lockBalancesForTransfer(
            UUID organizationId, UUID assetModelId, StockPlace source, StockPlace destination, Instant now) {
        String sourceKey = source.kind() + ":" + source.id();
        String destinationKey = destination.kind() + ":" + destination.id();
        boolean sourceFirst = sourceKey.compareTo(destinationKey) <= 0;
        BalanceState first = ensureAndLockPlace(organizationId, assetModelId, sourceFirst ? source : destination, now);
        BalanceState second = ensureAndLockPlace(organizationId, assetModelId, sourceFirst ? destination : source, now);
        return sourceFirst ? new TransferLock(first, second) : new TransferLock(second, first);
    }

    public void setBalanceQuantity(UUID balanceId, UUID organizationId, BigDecimal quantity, Instant now) {
        int changed = jdbcClient
                .sql(
                        "UPDATE consumable_stock_balance SET quantity=:quantity,updated_at=:now,version=version+1 WHERE id=:id AND organization_id=:organizationId AND archived_at IS NULL")
                .param("quantity", quantity)
                .param("now", toOffsetDateTime(now))
                .param("id", balanceId)
                .param("organizationId", organizationId)
                .update();
        if (changed != 1) throw new ArchiveConflictException("Restore archived balance before changing stock.");
    }

    private BalanceState ensureAndLockPlace(UUID org, UUID model, StockPlace place, Instant now) {
        if (place.containerAssetId() != null)
            return ensureAndLockContainerBalance(org, model, place.containerAssetId(), now);
        ensureLocationBalanceExists(org, model, place.locationId(), now);
        return jdbcClient
                .sql("""
                SELECT id,quantity,created_at,updated_at,version FROM consumable_stock_balance
                WHERE organization_id=:organizationId AND asset_model_id=:assetModelId AND location_id=:locationId AND archived_at IS NULL FOR UPDATE
                """)
                .param("organizationId", org)
                .param("assetModelId", model)
                .param("locationId", place.locationId())
                .query(ConsumableStockLedgerRepository::mapBalanceState)
                .optional()
                .orElseThrow(() -> new io.kellermann.tarpeisto.exception.ArchiveConflictException(
                        "Restore archived balance before moving stock."));
    }

    /**
     * Atomically applies {@code delta} (positive or negative) to the balance for {@code
     * (assetModelId, containerAssetId)}, creating the balance first if this is its first movement.
     * Returns empty if - and only if - applying {@code delta} would take the balance negative,
     * which also covers "no balance exists yet" (treated as a zero balance): a decrement against a
     * place with no stock is exactly as invalid as a decrement that would cross zero.
     */
    public Optional<BalanceState> applyDeltaToContainerBalance(
            UUID organizationId, UUID assetModelId, UUID containerAssetId, BigDecimal delta, Instant now) {
        if (delta.signum() > 0) {
            // Only worth creating an absent balance when it can move forward; a decrement against
            // an absent balance should simply fail below rather than manufacture a zero row first.
            ensureContainerBalanceExists(organizationId, assetModelId, containerAssetId, now);
        }
        requireActivePlace(organizationId, assetModelId, StockPlace.container(containerAssetId));
        return jdbcClient
                .sql("""
                        UPDATE consumable_stock_balance
                        SET quantity = quantity + :delta, updated_at = :now, version = version + 1
                        WHERE organization_id = :organizationId
                          AND asset_model_id = :assetModelId
                          AND container_asset_id = :containerAssetId
                          AND archived_at IS NULL AND quantity + :delta >= 0
                        RETURNING id, quantity, created_at, updated_at, version
                        """)
                .param("delta", delta)
                .param("now", toOffsetDateTime(now))
                .param("organizationId", organizationId)
                .param("assetModelId", assetModelId)
                .param("containerAssetId", containerAssetId)
                .query(ConsumableStockLedgerRepository::mapBalanceState)
                .optional();
    }

    /**
     * Locks both balances of a transfer in canonical order (ascending {@code container_asset_id}),
     * creating either side that does not exist yet as a zero balance first. The caller is
     * responsible for validating the source has enough quantity and then persisting both new
     * quantities with {@link #setContainerBalanceQuantity} while still holding these locks, inside
     * the same transaction.
     */
    public TransferLock lockContainerBalancesForTransfer(
            UUID organizationId,
            UUID assetModelId,
            UUID sourceContainerAssetId,
            UUID destinationContainerAssetId,
            Instant now) {
        boolean sourceFirst = sourceContainerAssetId.compareTo(destinationContainerAssetId) <= 0;
        UUID first = sourceFirst ? sourceContainerAssetId : destinationContainerAssetId;
        UUID second = sourceFirst ? destinationContainerAssetId : sourceContainerAssetId;

        BalanceState firstLock = ensureAndLockContainerBalance(organizationId, assetModelId, first, now);
        BalanceState secondLock = ensureAndLockContainerBalance(organizationId, assetModelId, second, now);

        return sourceFirst ? new TransferLock(firstLock, secondLock) : new TransferLock(secondLock, firstLock);
    }

    /** Writes a new quantity for a balance already locked by this transaction (see the transfer path above). */
    public void setContainerBalanceQuantity(UUID balanceId, UUID organizationId, BigDecimal newQuantity, Instant now) {
        int changed = jdbcClient
                .sql("""
                        UPDATE consumable_stock_balance
                        SET quantity = :quantity, updated_at = :now, version = version + 1
                        WHERE id = :id AND organization_id = :organizationId AND archived_at IS NULL
                        """)
                .param("quantity", newQuantity)
                .param("now", toOffsetDateTime(now))
                .param("id", balanceId)
                .param("organizationId", organizationId)
                .update();
        if (changed != 1) throw new ArchiveConflictException("Restore archived balance before changing stock.");
    }

    private BalanceState ensureAndLockContainerBalance(
            UUID organizationId, UUID assetModelId, UUID containerAssetId, Instant now) {
        ensureContainerBalanceExists(organizationId, assetModelId, containerAssetId, now);
        return jdbcClient
                .sql("""
                        SELECT id, quantity, created_at, updated_at, version FROM consumable_stock_balance
                        WHERE organization_id = :organizationId
                          AND asset_model_id = :assetModelId
                          AND container_asset_id = :containerAssetId AND archived_at IS NULL
                        FOR UPDATE
                        """)
                .param("organizationId", organizationId)
                .param("assetModelId", assetModelId)
                .param("containerAssetId", containerAssetId)
                .query(ConsumableStockLedgerRepository::mapBalanceState)
                .optional()
                .orElseThrow(() -> new io.kellermann.tarpeisto.exception.ArchiveConflictException(
                        "Restore archived balance before moving stock."));
    }

    /**
     * pgjdbc's {@code getObject(String, Class)} only converts {@code timestamptz} to {@link
     * OffsetDateTime} (or the other {@code java.time} local/offset types) - never directly to {@link
     * Instant} - so timestamps are read as {@link OffsetDateTime} here and converted afterward.
     */
    private static BalanceState mapBalanceState(ResultSet rs, int rowNum) throws SQLException {
        return new BalanceState(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("quantity"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }

    /**
     * pgjdbc binds a {@code timestamptz} parameter natively only for {@link OffsetDateTime} (and the
     * other {@code java.time} local/offset types) - never for a bare {@link Instant}, which fails
     * with "Cannot infer the SQL type" (untyped {@code setObject}) or "Cannot cast ... to
     * TIMESTAMP_WITH_TIMEZONE" (explicitly typed). Every {@code now} parameter is converted through
     * this method before being bound.
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void ensureContainerBalanceExists(
            UUID organizationId, UUID assetModelId, UUID containerAssetId, Instant now) {
        jdbcClient
                .sql("""
                        INSERT INTO consumable_stock_balance
                            (id, organization_id, asset_model_id, container_asset_id, quantity, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :assetModelId, :containerAssetId, 0, :now, :now, 0)
                        ON CONFLICT (asset_model_id, container_asset_id) WHERE container_asset_id IS NOT NULL DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("assetModelId", assetModelId)
                .param("containerAssetId", containerAssetId)
                .param("now", toOffsetDateTime(now))
                .update();
    }

    private void ensureLocationBalanceExists(UUID organizationId, UUID assetModelId, UUID locationId, Instant now) {
        jdbcClient
                .sql("""
                INSERT INTO consumable_stock_balance (id,organization_id,asset_model_id,location_id,quantity,created_at,updated_at,version)
                VALUES (:id,:organizationId,:assetModelId,:locationId,0,:now,:now,0)
                ON CONFLICT (asset_model_id,location_id) WHERE location_id IS NOT NULL DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("assetModelId", assetModelId)
                .param("locationId", locationId)
                .param("now", toOffsetDateTime(now))
                .update();
    }

    private void requireActivePlace(UUID org, UUID model, StockPlace place) {
        boolean archived = jdbcClient
                .sql(
                        "SELECT archived_at IS NOT NULL FROM consumable_stock_balance WHERE organization_id=:org AND asset_model_id=:model AND "
                                + (place.containerAssetId() != null ? "container_asset_id" : "location_id")
                                + "=:place FOR UPDATE")
                .param("org", org)
                .param("model", model)
                .param("place", place.id())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (archived)
            throw new io.kellermann.tarpeisto.exception.ArchiveConflictException(
                    "Restore archived balance before changing stock.");
    }
}
