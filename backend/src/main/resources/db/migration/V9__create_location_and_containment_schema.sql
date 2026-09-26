-- Phase 4: organization-scoped locations and current physical containment.
CREATE TABLE location (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    name VARCHAR(160) NOT NULL,
    description TEXT,
    parent_location_id UUID,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_location_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_location_parent_same_organization FOREIGN KEY (parent_location_id, organization_id)
        REFERENCES location (id, organization_id),
    CONSTRAINT ck_location_not_own_parent CHECK (parent_location_id IS NULL OR parent_location_id <> id)
);
CREATE INDEX ix_location_organization_parent ON location (organization_id, parent_location_id);

ALTER TABLE physical_asset
    ADD COLUMN direct_location_id UUID,
    ADD COLUMN parent_container_asset_id UUID,
    ADD CONSTRAINT fk_physical_asset_direct_location_same_organization
        FOREIGN KEY (direct_location_id, organization_id) REFERENCES location (id, organization_id),
    ADD CONSTRAINT fk_physical_asset_parent_container_same_organization
        FOREIGN KEY (parent_container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    ADD CONSTRAINT ck_physical_asset_one_direct_place CHECK (
        NOT (direct_location_id IS NOT NULL AND parent_container_asset_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_physical_asset_not_own_parent CHECK (
        parent_container_asset_id IS NULL OR parent_container_asset_id <> id
    );
CREATE INDEX ix_physical_asset_direct_location ON physical_asset (organization_id, direct_location_id)
    WHERE direct_location_id IS NOT NULL;
CREATE INDEX ix_physical_asset_parent_container ON physical_asset (organization_id, parent_container_asset_id)
    WHERE parent_container_asset_id IS NOT NULL;

ALTER TABLE consumable_stock_balance
    DROP CONSTRAINT ck_consumable_stock_balance_location_not_yet_supported,
    ADD CONSTRAINT fk_consumable_stock_balance_location_same_organization
        FOREIGN KEY (location_id, organization_id) REFERENCES location (id, organization_id);

-- A container with direct assets or consumable stock cannot be archived. The service performs the
-- user-facing guard; these indexes support that guard and the recursive path projections.
CREATE INDEX ix_consumable_stock_balance_location ON consumable_stock_balance (organization_id, location_id)
    WHERE location_id IS NOT NULL;
