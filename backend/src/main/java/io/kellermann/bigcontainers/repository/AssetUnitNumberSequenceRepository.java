package io.kellermann.bigcontainers.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Atomically allocates model-local unit numbers (specification section 8.2: "Units created in bulk
 * receive sequential model-local unit numbers"), using raw SQL rather than Spring Data JPA per
 * docs/DEVELOPMENT_POLICIES.md section 5.2 ("Spring {@code JdbcClient} handles ... bulk
 * operations").
 *
 * <p><strong>Concurrency safety:</strong> {@link #allocateRange} issues a single {@code UPDATE
 * asset_model SET next_unit_number = next_unit_number + :count ... RETURNING next_unit_number}
 * statement. PostgreSQL takes a row-level lock on the targeted {@code asset_model} row for the
 * duration of that statement, so a second, concurrent call against the <em>same</em> model blocks
 * until the first transaction commits or rolls back, then reads the already-incremented value.
 * Two concurrent bulk creations therefore always receive disjoint, non-overlapping ranges - this
 * holds under the default {@code READ COMMITTED} isolation level and needs no explicit {@code
 * SELECT ... FOR UPDATE}. Because the {@code UPDATE} runs inside {@code AssetService}'s own
 * {@code @Transactional} method (sharing its connection via Spring's transaction synchronization),
 * a creation that later fails and rolls back also rolls back its counter reservation, so no
 * numbers are silently burned. The {@code uk_physical_asset_model_unit_number} unique constraint
 * in {@code V5__create_asset_schema.sql} is the belt-and-suspenders backstop if this invariant is
 * ever bypassed.
 */
@Repository
public class AssetUnitNumberSequenceRepository {

    private final JdbcClient jdbcClient;

    public AssetUnitNumberSequenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Reserves {@code count} consecutive unit numbers for {@code assetModelId} and returns the
     * first one; the reserved range is {@code [result, result + count - 1]}.
     */
    public int allocateRange(UUID organizationId, UUID assetModelId, int count) {
        int firstUnusedAfterAllocation = jdbcClient
                .sql("""
                        UPDATE asset_model
                        SET next_unit_number = next_unit_number + :count
                        WHERE id = :assetModelId AND organization_id = :organizationId
                        RETURNING next_unit_number
                        """)
                .param("count", count)
                .param("assetModelId", assetModelId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single();
        return firstUnusedAfterAllocation - count;
    }
}
