-- Phase 2a catalog: categories, asset models, and model-defined custom field definitions
-- (specification sections 5-7, implementation plan section 4.1). Table/column names are
-- singular snake_case per docs/DEVELOPMENT_POLICIES.md section 5.2.
--
-- Every tenant-owned table below carries organization_id, and every child table that
-- references an organization-scoped parent uses a composite foreign key
-- (parent_id, organization_id) against a matching UNIQUE(id, organization_id) constraint on the
-- parent - never a plain UUID foreign key alone - so a cross-organization reference is rejected
-- by the database itself, not only by application code (specification section 3.2,
-- docs/DEVELOPMENT_POLICIES.md section 5.2).
--
-- physical_asset, asset_custom_field_value, consumable_stock_balance, and stock_movement
-- (implementation plan section 4.1, items 5-8) are explicitly out of scope for this migration
-- and are left for Phase 2b. This migration's tables are designed so those later tables attach
-- with composite (id, organization_id) foreign keys exactly like the ones below.

-- ---------------------------------------------------------------------------------------------
-- category (specification section 5)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE category (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    name VARCHAR(255) NOT NULL,
    -- VARCHAR, not CHAR: CHAR is blank-padded, and Hibernate's schema validation maps a
    -- String field of length 7 to varchar(7), so CHAR(7) fails ddl-auto=validate at startup.
    color VARCHAR(7) NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_category_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_category_color CHECK (color ~ '^#[0-9A-F]{6}$')
);

CREATE INDEX ix_category_organization ON category (organization_id);

-- Category names are unique within an organization while active (specification section 5).
-- Archiving frees the name for reuse: nothing in the specification requires a name to stay
-- permanently blocked after one archived category, and historical records still resolve the
-- archived category by id regardless of this index.
CREATE UNIQUE INDEX uk_category_org_name_active ON category (organization_id, lower(name))
    WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- asset_model (specification section 6)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE asset_model (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category_id UUID NOT NULL,
    replacement_url VARCHAR(2048),
    tracking_mode VARCHAR(20) NOT NULL,
    stock_unit_label VARCHAR(50),
    low_stock_threshold NUMERIC(12, 3),
    can_contain_assets BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_asset_model_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_asset_model_category FOREIGN KEY (category_id, organization_id)
        REFERENCES category (id, organization_id),
    CONSTRAINT ck_asset_model_tracking_mode CHECK (tracking_mode IN ('SERIALIZED_ASSET', 'QUANTITY_STOCK')),
    -- Specification section 6.2: "Quantity-tracked models cannot be container-capable."
    CONSTRAINT ck_asset_model_quantity_not_container CHECK (
        tracking_mode <> 'QUANTITY_STOCK' OR can_contain_assets = FALSE
    ),
    -- A stock unit label and low-stock threshold only mean something for a quantity-tracked
    -- model (specification section 6.1); a serialized model never carries them.
    CONSTRAINT ck_asset_model_stock_unit_label_scope CHECK (
        (tracking_mode = 'QUANTITY_STOCK' AND stock_unit_label IS NOT NULL)
        OR (tracking_mode = 'SERIALIZED_ASSET' AND stock_unit_label IS NULL)
    ),
    CONSTRAINT ck_asset_model_low_stock_threshold_scope CHECK (
        low_stock_threshold IS NULL
        OR (tracking_mode = 'QUANTITY_STOCK' AND low_stock_threshold >= 0)
    )
);

CREATE INDEX ix_asset_model_organization ON asset_model (organization_id);
CREATE INDEX ix_asset_model_category ON asset_model (category_id, organization_id);

CREATE UNIQUE INDEX uk_asset_model_org_name_active ON asset_model (organization_id, lower(name))
    WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- model_custom_field / model_custom_field_option (specification section 7)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE model_custom_field (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_model_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    data_type VARCHAR(20) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_model_custom_field_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_model_custom_field_asset_model FOREIGN KEY (asset_model_id, organization_id)
        REFERENCES asset_model (id, organization_id),
    CONSTRAINT ck_model_custom_field_data_type CHECK (data_type IN ('STRING', 'DROPDOWN', 'DATE'))
);

CREATE INDEX ix_model_custom_field_asset_model ON model_custom_field (asset_model_id, organization_id);

-- Field names are unique within a model while active (specification section 7.2).
CREATE UNIQUE INDEX uk_model_custom_field_model_name_active
    ON model_custom_field (asset_model_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE model_custom_field_option (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    model_custom_field_id UUID NOT NULL,
    value VARCHAR(255) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_model_custom_field_option_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_model_custom_field_option_field FOREIGN KEY (model_custom_field_id, organization_id)
        REFERENCES model_custom_field (id, organization_id)
);

CREATE INDEX ix_model_custom_field_option_field
    ON model_custom_field_option (model_custom_field_id, organization_id);

CREATE UNIQUE INDEX uk_model_custom_field_option_field_value_active
    ON model_custom_field_option (model_custom_field_id, lower(value)) WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Cross-table invariant (specification section 6.2): "Quantity-tracked models cannot ... define
-- per-unit custom fields." A single-table CHECK constraint cannot express a rule that spans
-- model_custom_field and asset_model, so a trigger backs the service-layer guard
-- (AssetModelService/ModelCustomFieldService) with a real database-level safety net, per
-- docs/DEVELOPMENT_POLICIES.md section 5.2 ("Important invariants are backed by PostgreSQL
-- constraints as well as service validation").
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fn_reject_custom_field_on_quantity_model() RETURNS TRIGGER AS $$
DECLARE
    model_tracking_mode VARCHAR(20);
BEGIN
    SELECT tracking_mode INTO model_tracking_mode FROM asset_model WHERE id = NEW.asset_model_id;
    IF model_tracking_mode = 'QUANTITY_STOCK' THEN
        RAISE EXCEPTION 'A QUANTITY_STOCK asset model cannot define custom fields'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_model_custom_field_reject_quantity_model
    BEFORE INSERT OR UPDATE ON model_custom_field
    FOR EACH ROW EXECUTE FUNCTION fn_reject_custom_field_on_quantity_model();

-- Mirrors the same rule from the other direction: an asset_model cannot be switched to
-- QUANTITY_STOCK while it still has any custom field definitions (archived or active - an
-- archived definition still records historical values shaped by SERIALIZED_ASSET semantics).
CREATE FUNCTION fn_reject_quantity_mode_with_custom_fields() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tracking_mode = 'QUANTITY_STOCK' AND OLD.tracking_mode <> 'QUANTITY_STOCK'
        AND EXISTS (SELECT 1 FROM model_custom_field WHERE asset_model_id = NEW.id) THEN
        RAISE EXCEPTION 'Cannot change tracking mode to QUANTITY_STOCK: custom field definitions exist'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_asset_model_reject_quantity_mode_with_custom_fields
    BEFORE UPDATE ON asset_model
    FOR EACH ROW EXECUTE FUNCTION fn_reject_quantity_mode_with_custom_fields();
