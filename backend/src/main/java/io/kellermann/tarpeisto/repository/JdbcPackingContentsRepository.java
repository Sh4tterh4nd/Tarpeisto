package io.kellermann.tarpeisto.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tenant-scoped bulk names for the bounded contents/requirement page, never individual lookups. */
@Repository
public class JdbcPackingContentsRepository {
    private final JdbcClient jdbc;

    public JdbcPackingContentsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssetIdentity> assets(UUID organizationId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT a.id, a.public_code, coalesce(a.individual_name,m.name || ' ' || a.unit_number),
                       a.asset_model_id, m.name, a.archived_at IS NULL AND a.lifecycle_state='ACTIVE'
                FROM physical_asset a JOIN asset_model m ON m.id=a.asset_model_id AND m.organization_id=a.organization_id
                WHERE a.organization_id=:org AND a.id IN (:ids)
                """)
                .param("org", organizationId)
                .param("ids", ids)
                .query((rs, row) -> new AssetIdentity(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getString(5),
                        rs.getBoolean(6)))
                .list();
    }

    public List<ModelIdentity> models(UUID organizationId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id,name,stock_unit_label FROM asset_model WHERE organization_id=:org AND id IN (:ids)")
                .param("org", organizationId)
                .param("ids", ids)
                .query((rs, row) -> new ModelIdentity(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    public record AssetIdentity(
            UUID assetId,
            String publicCode,
            String displayName,
            UUID assetModelId,
            String assetModelName,
            boolean active) {}

    public record ModelIdentity(UUID assetModelId, String name, String unitLabel) {}
}
