-- Phase 2b (part 1): physical assets, model-defined custom field values, and asset
-- lifecycle/condition history (specification sections 7-9, 25; implementation plan section 4.1
-- items 5, 6, 9). Table/column names are singular snake_case per
-- docs/DEVELOPMENT_POLICIES.md section 5.2.
--
-- consumable_stock_balance and stock_movement (implementation plan section 4.1 items 7-8) remain
-- out of scope for this migration and are left for a following task.
--
-- Locations and physical containment (specification sections 10-11) are Phase 4's subject and are
-- deliberately NOT added here: physical_asset carries no direct-location or parent-container
-- column yet. Phase 4 adds both together with the `location` table and its cycle-safety rules;
-- until then this table is left with no columns that would need reworking, per the Phase 2b task
-- scope.

-- ---------------------------------------------------------------------------------------------
-- asset_model: add the atomic model-local unit-number counter (specification section 8.2:
-- "Units created in bulk receive sequential model-local unit numbers").
--
-- Concurrency-safety design: allocating a range of unit numbers is a single
-- "UPDATE asset_model SET next_unit_number = next_unit_number + :count ... RETURNING
-- next_unit_number" statement (see AssetUnitNumberSequenceRepository). PostgreSQL takes a
-- row-level lock for the duration of that UPDATE, so two concurrent bulk-creation transactions
-- against the SAME asset_model row are serialized by the database itself - the second blocks
-- until the first commits or rolls back, then reads the already-incremented value. This holds
-- under the default READ COMMITTED isolation level and needs no explicit SELECT ... FOR UPDATE.
-- A rolled-back creation also rolls back its counter reservation, so no numbers are silently
-- burned by a failed creation.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE asset_model ADD COLUMN next_unit_number INT NOT NULL DEFAULT 1;

-- A composite (id, model_custom_field_id) uniqueness lets asset_custom_field_value below use a
-- genuine composite foreign key to guarantee "a dropdown value must reference ... an option
-- belonging to the correct definition" (specification section 7.2) at the database level, instead
-- of only in a trigger.
ALTER TABLE model_custom_field_option
    ADD CONSTRAINT uk_model_custom_field_option_id_field UNIQUE (id, model_custom_field_id);

-- ---------------------------------------------------------------------------------------------
-- physical_asset (specification section 8)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE physical_asset (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_model_id UUID NOT NULL,
    -- VARCHAR(16), not the current 6-character length: ADR-0002 requires the schema to accept a
    -- longer future code format without a migration, with the final character remaining the
    -- checksum. Entity mapping below matches this length deliberately (see V4's CHAR(7) note on
    -- why a mismatched column length/type fails ddl-auto=validate for the whole context).
    public_code VARCHAR(16) NOT NULL,
    unit_number INT NOT NULL,
    individual_name VARCHAR(255),
    condition VARCHAR(20) NOT NULL DEFAULT 'GOOD',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    purchase_date DATE,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_physical_asset_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_physical_asset_asset_model FOREIGN KEY (asset_model_id, organization_id)
        REFERENCES asset_model (id, organization_id),
    -- ADR-0002 "Generation and uniqueness": "A database unique constraint covers
    -- (organization_id, public_code)." Codes are immutable and never reused, so this constraint
    -- is never relaxed by an archive/restore cycle.
    CONSTRAINT uk_physical_asset_org_public_code UNIQUE (organization_id, public_code),
    -- Specification section 8.2 / this migration's header comment: model-local unit numbers are
    -- sequential per model and must never collide, backing AssetUnitNumberSequenceRepository's
    -- atomic allocation with a real constraint.
    CONSTRAINT uk_physical_asset_model_unit_number UNIQUE (asset_model_id, unit_number),
    CONSTRAINT ck_physical_asset_unit_number_positive CHECK (unit_number > 0),
    CONSTRAINT ck_physical_asset_condition CHECK (condition IN ('GOOD', 'DAMAGED')),
    CONSTRAINT ck_physical_asset_lifecycle_state
        CHECK (lifecycle_state IN ('ACTIVE', 'LOST', 'DESTROYED', 'RETIRED'))
);

CREATE INDEX ix_physical_asset_organization ON physical_asset (organization_id);
CREATE INDEX ix_physical_asset_asset_model ON physical_asset (asset_model_id, organization_id);

-- ---------------------------------------------------------------------------------------------
-- Cross-table invariant (specification section 6.2): "Each real-world unit is a physical asset"
-- applies only to a SERIALIZED_ASSET model - a QUANTITY_STOCK model "does not receive public
-- asset codes, individual labels, condition, or lifecycle state." Mirrors the
-- tr_model_custom_field_reject_quantity_model trigger in V4 for the same reason: a single-table
-- CHECK constraint cannot see the referenced asset_model's tracking_mode.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fn_reject_physical_asset_on_quantity_model() RETURNS TRIGGER AS $$
DECLARE
    model_tracking_mode VARCHAR(20);
BEGIN
    SELECT tracking_mode INTO model_tracking_mode FROM asset_model WHERE id = NEW.asset_model_id;
    IF model_tracking_mode = 'QUANTITY_STOCK' THEN
        RAISE EXCEPTION 'A QUANTITY_STOCK asset model cannot have physical assets'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_physical_asset_reject_quantity_model
    BEFORE INSERT OR UPDATE ON physical_asset
    FOR EACH ROW EXECUTE FUNCTION fn_reject_physical_asset_on_quantity_model();

-- Mirrors the same rule from the other direction: an asset_model cannot be switched to
-- QUANTITY_STOCK while it still has any physical_asset rows.
CREATE FUNCTION fn_reject_quantity_mode_with_assets() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tracking_mode = 'QUANTITY_STOCK' AND OLD.tracking_mode <> 'QUANTITY_STOCK'
        AND EXISTS (SELECT 1 FROM physical_asset WHERE asset_model_id = NEW.id) THEN
        RAISE EXCEPTION 'Cannot change tracking mode to QUANTITY_STOCK: physical assets exist'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_asset_model_reject_quantity_mode_with_assets
    BEFORE UPDATE ON asset_model
    FOR EACH ROW EXECUTE FUNCTION fn_reject_quantity_mode_with_assets();

-- ---------------------------------------------------------------------------------------------
-- asset_custom_field_value (specification section 7): "Custom-field definitions are created on a
-- serialized model, but their values exist only on physical assets of that model." Exactly one of
-- string_value / date_value / option_id is set, matching the owning model_custom_field's
-- data_type - enforced twice: AssetCustomFieldValue's own constructor/update guard, and the
-- trigger below as the database-level backstop (docs/DEVELOPMENT_POLICIES.md section 5.2).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE asset_custom_field_value (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_id UUID NOT NULL,
    model_custom_field_id UUID NOT NULL,
    string_value TEXT,
    date_value DATE,
    option_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_asset_custom_field_value_id_organization UNIQUE (id, organization_id),
    -- One value per field per asset (specification section 7: values are attached one-to-one to
    -- an asset/field pair; "required" and "metadata incomplete" are both defined in terms of this
    -- pairing existing or not).
    CONSTRAINT uk_asset_custom_field_value_asset_field UNIQUE (asset_id, model_custom_field_id),
    CONSTRAINT fk_asset_custom_field_value_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_asset_custom_field_value_field FOREIGN KEY (model_custom_field_id, organization_id)
        REFERENCES model_custom_field (id, organization_id),
    -- Composite FK against the new uk_model_custom_field_option_id_field constraint above: the
    -- database itself rejects an option that does not belong to model_custom_field_id, which is
    -- half of "Dropdown values must reference ... an option belonging to the correct definition"
    -- (specification section 7.2). Whether the option is still ACTIVE at write time is validated
    -- in the service layer only (AssetService), deliberately not by a permanent constraint here:
    -- archiving an option later must not invalidate historical values that referenced it while it
    -- was active (specification section 7.2: "Removing a field archives its definition and
    -- historical values").
    CONSTRAINT fk_asset_custom_field_value_option FOREIGN KEY (option_id, model_custom_field_id)
        REFERENCES model_custom_field_option (id, model_custom_field_id)
);

CREATE INDEX ix_asset_custom_field_value_asset ON asset_custom_field_value (asset_id, organization_id);
CREATE INDEX ix_asset_custom_field_value_field ON asset_custom_field_value (model_custom_field_id);

CREATE FUNCTION fn_validate_asset_custom_field_value() RETURNS TRIGGER AS $$
DECLARE
    field_data_type VARCHAR(20);
    asset_model UUID;
    field_model UUID;
BEGIN
    SELECT data_type, asset_model_id INTO field_data_type, field_model
        FROM model_custom_field WHERE id = NEW.model_custom_field_id;
    SELECT asset_model_id INTO asset_model FROM physical_asset WHERE id = NEW.asset_id;

    IF asset_model IS DISTINCT FROM field_model THEN
        RAISE EXCEPTION 'A custom field value must reference a field belonging to the asset''s model'
            USING ERRCODE = '23514';
    END IF;

    IF field_data_type = 'STRING' THEN
        IF NEW.string_value IS NULL OR NEW.date_value IS NOT NULL OR NEW.option_id IS NOT NULL THEN
            RAISE EXCEPTION 'A STRING custom field value must set only string_value' USING ERRCODE = '23514';
        END IF;
    ELSIF field_data_type = 'DATE' THEN
        IF NEW.date_value IS NULL OR NEW.string_value IS NOT NULL OR NEW.option_id IS NOT NULL THEN
            RAISE EXCEPTION 'A DATE custom field value must set only date_value' USING ERRCODE = '23514';
        END IF;
    ELSIF field_data_type = 'DROPDOWN' THEN
        IF NEW.option_id IS NULL OR NEW.string_value IS NOT NULL OR NEW.date_value IS NOT NULL THEN
            RAISE EXCEPTION 'A DROPDOWN custom field value must set only option_id' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_asset_custom_field_value_validate
    BEFORE INSERT OR UPDATE ON asset_custom_field_value
    FOR EACH ROW EXECUTE FUNCTION fn_validate_asset_custom_field_value();

-- ---------------------------------------------------------------------------------------------
-- asset_state_history (specification sections 8.3/8.4/25): an append-only record of every
-- condition or lifecycle transition. Rows are never updated or deleted by application code,
-- mirroring activity_log's immutability (V3).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE asset_state_history (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_id UUID NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    previous_value VARCHAR(20) NOT NULL,
    new_value VARCHAR(20) NOT NULL,
    reason TEXT,
    actor_user_id UUID REFERENCES app_user (id),
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_asset_state_history_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT ck_asset_state_history_change_type CHECK (change_type IN ('CONDITION', 'LIFECYCLE')),
    CONSTRAINT ck_asset_state_history_condition_values CHECK (
        change_type <> 'CONDITION'
        OR (previous_value IN ('GOOD', 'DAMAGED') AND new_value IN ('GOOD', 'DAMAGED'))
    ),
    CONSTRAINT ck_asset_state_history_lifecycle_values CHECK (
        change_type <> 'LIFECYCLE'
        OR (previous_value IN ('ACTIVE', 'LOST', 'DESTROYED', 'RETIRED')
            AND new_value IN ('ACTIVE', 'LOST', 'DESTROYED', 'RETIRED'))
    )
);

CREATE INDEX ix_asset_state_history_asset ON asset_state_history (asset_id, changed_at DESC);
