-- Phase 2b (part 2): consumable stock balances and the immutable stock-movement ledger
-- (specification section 6.4, also 6.2/25/28; implementation plan section 4.1 items 7-8, all of
-- 4.5, and the "concurrent consumable issues overspend stock" risk row in section 18).
--
-- ---------------------------------------------------------------------------------------------
-- Stock-place ordering conflict (AGENTS.md: "Resolve conflicts in the documents before writing
-- code") and its resolution:
--
-- Specification section 6.4 says a balance belongs to "exactly one stock place: either a direct
-- location or a container asset." The `location` table does not exist until Phase 4 (specification
-- section 10), and physical containment is also Phase 4's subject - yet the implementation plan
-- places consumable balances in Phase 2. Resolution: implement the stock-place kind that exists
-- today - a container asset (`physical_asset` already exists, and a container-capable model's
-- units already act as containers per specification section 6.3) - and shape the schema so Phase 4
-- can add the location-based stock place WITHOUT rewriting any existing row.
--
-- Concretely: consumable_stock_balance carries BOTH `container_asset_id` and `location_id` today.
-- Exactly one of the two must be non-null (ck_consumable_stock_balance_exactly_one_place), the same
-- "exactly one of several nullable columns" idiom V5's asset_custom_field_value/
-- fn_validate_asset_custom_field_value already uses for exactly-one-of-string/date/option. A second
-- CHECK (ck_consumable_stock_balance_location_not_yet_supported) forces location_id to stay NULL on
-- every row created before Phase 4, because there is no `location` table yet to validate it against
-- and an unvalidated free-floating UUID would be a real data-integrity hole (specification section
-- 28). `location_id` is deliberately NOT mapped by the Java entity at all in this task - see
-- ConsumableStock's Javadoc - so nothing in this codebase can even attempt to set it yet.
--
-- Exact Phase 4 impact (metadata-only DDL, no data migration, no row rewritten):
--   1. Create the `location` table (specification section 10).
--   2. ADD CONSTRAINT fk_consumable_stock_balance_location FOREIGN KEY (location_id,
--      organization_id) REFERENCES location (id, organization_id).
--   3. DROP CONSTRAINT ck_consumable_stock_balance_location_not_yet_supported (or redefine it to
--      require the row's organization to have location support - not needed for release 1).
--   4. Map `locationId` on the Java entity and add a sealed `StockPlace` hierarchy
--      (ContainerAssetPlace / LocationPlace) so callers get a closed choice instead of two loose
--      nullable UUIDs.
-- Every row inserted before Phase 4 already satisfies the widened constraint (location_id is NULL,
-- container_asset_id is set), so nothing above touches existing data - only the constraint
-- definitions change.
--
-- "At most one active balance per (model, stock place)" (specification section 6.4) is enforced by
-- two partial unique indexes below, one per stock-place kind, so Phase 4 adds a third kind by
-- adding a third partial index, never by altering the existing two.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE consumable_stock_balance (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_model_id UUID NOT NULL,
    container_asset_id UUID,
    location_id UUID,
    -- NUMERIC(14,3), never a binary floating-point type: specification section 6.2 requires exact
    -- decimal precision up to three places ("Quantity precision supports up to three decimal
    -- places"), and money-like exact quantities must never be represented as float/double, which
    -- cannot represent 0.1 exactly and would let rounding error silently create or destroy stock
    -- across many small movements. NUMERIC(14,3) matches asset_model.low_stock_threshold's existing
    -- precision/scale (V4) so a balance can always be compared directly against its model's
    -- threshold without a cast.
    quantity NUMERIC(14, 3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_consumable_stock_balance_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_consumable_stock_balance_asset_model FOREIGN KEY (asset_model_id, organization_id)
        REFERENCES asset_model (id, organization_id),
    CONSTRAINT fk_consumable_stock_balance_container_asset FOREIGN KEY (container_asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT ck_consumable_stock_balance_exactly_one_place CHECK (
        (container_asset_id IS NOT NULL AND location_id IS NULL)
        OR (container_asset_id IS NULL AND location_id IS NOT NULL)
    ),
    CONSTRAINT ck_consumable_stock_balance_location_not_yet_supported CHECK (location_id IS NULL),
    CONSTRAINT ck_consumable_stock_balance_quantity_non_negative CHECK (quantity >= 0)
);

CREATE INDEX ix_consumable_stock_balance_organization ON consumable_stock_balance (organization_id);
CREATE INDEX ix_consumable_stock_balance_asset_model ON consumable_stock_balance (asset_model_id, organization_id);
CREATE INDEX ix_consumable_stock_balance_container_asset ON consumable_stock_balance (container_asset_id)
    WHERE container_asset_id IS NOT NULL;

-- "At most one active balance for a model at a given stock place" (specification section 6.4).
CREATE UNIQUE INDEX uk_consumable_stock_balance_model_container
    ON consumable_stock_balance (asset_model_id, container_asset_id) WHERE container_asset_id IS NOT NULL;
CREATE UNIQUE INDEX uk_consumable_stock_balance_model_location
    ON consumable_stock_balance (asset_model_id, location_id) WHERE location_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Cross-table invariant (specification section 6.2/6.4): only a QUANTITY_STOCK model may have a
-- consumable stock balance; a SERIALIZED_ASSET model must never have one. Mirrors V5's
-- fn_reject_physical_asset_on_quantity_model from the other direction.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fn_reject_stock_balance_on_serialized_model() RETURNS TRIGGER AS $$
DECLARE
    model_tracking_mode VARCHAR(20);
BEGIN
    SELECT tracking_mode INTO model_tracking_mode FROM asset_model WHERE id = NEW.asset_model_id;
    IF model_tracking_mode <> 'QUANTITY_STOCK' THEN
        RAISE EXCEPTION 'A SERIALIZED_ASSET model must never have a consumable stock balance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_stock_balance_reject_serialized_model
    BEFORE INSERT OR UPDATE ON consumable_stock_balance
    FOR EACH ROW EXECUTE FUNCTION fn_reject_stock_balance_on_serialized_model();

-- Mirrors the same rule from the other direction: an asset_model cannot be switched away from
-- QUANTITY_STOCK while it still has any consumable_stock_balance row (specification section 6.2:
-- "Changing tracking mode is prohibited after ... stock balances ... exist").
CREATE FUNCTION fn_reject_non_quantity_mode_with_stock_balances() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tracking_mode <> 'QUANTITY_STOCK' AND OLD.tracking_mode = 'QUANTITY_STOCK'
        AND EXISTS (SELECT 1 FROM consumable_stock_balance WHERE asset_model_id = NEW.id) THEN
        RAISE EXCEPTION 'Cannot change tracking mode away from QUANTITY_STOCK: stock balances exist'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_asset_model_reject_non_quantity_mode_with_stock_balances
    BEFORE UPDATE ON asset_model
    FOR EACH ROW EXECUTE FUNCTION fn_reject_non_quantity_mode_with_stock_balances();

-- A stock place's container asset must belong to a container-capable model (specification section
-- 6.3: "every physical asset of a container-capable model can act as a container" - the converse
-- also holds: a non-container-capable model's asset cannot).
CREATE FUNCTION fn_reject_stock_balance_non_container_asset() RETURNS TRIGGER AS $$
DECLARE
    asset_can_contain BOOLEAN;
BEGIN
    IF NEW.container_asset_id IS NOT NULL THEN
        SELECT am.can_contain_assets INTO asset_can_contain
        FROM physical_asset pa JOIN asset_model am ON am.id = pa.asset_model_id
        WHERE pa.id = NEW.container_asset_id;
        IF asset_can_contain IS NOT TRUE THEN
            RAISE EXCEPTION 'A consumable stock balance''s container asset must belong to a container-capable model'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_stock_balance_reject_non_container_asset
    BEFORE INSERT OR UPDATE ON consumable_stock_balance
    FOR EACH ROW EXECUTE FUNCTION fn_reject_stock_balance_non_container_asset();

-- ---------------------------------------------------------------------------------------------
-- stock_movement (specification section 6.4): the immutable, append-only record of every balance
-- change. `event_reference_id` and `audit_reference_id` are deliberately nullable columns with NO
-- foreign key: events/bookings (Phase 7) and audits (Phase 9) do not exist yet, and this task's
-- scope explicitly leaves them as a seam rather than inventing placeholder tables. Phase 7/9 add
-- the matching `fk_stock_movement_event`/`fk_stock_movement_audit` constraints later; no existing
-- row needs to change since both columns are already NULL wherever they are not used today.
--
-- `resulting_quantity` is a denormalized snapshot of the owning balance's quantity immediately
-- after this movement was applied. It is not authoritative (consumable_stock_balance.quantity is),
-- but it lets a ledger view render "balance after this movement" without recomputing a running sum,
-- and it gives the reconciliation tests below an extra, independent cross-check.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE stock_movement (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    consumable_stock_balance_id UUID NOT NULL,
    quantity_delta NUMERIC(14, 3) NOT NULL,
    resulting_quantity NUMERIC(14, 3) NOT NULL,
    stock_unit_label VARCHAR(50) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    actor_user_id UUID REFERENCES app_user (id),
    note TEXT,
    -- Links a transfer's two legs (source decrement + destination increment) as one operation
    -- (specification section 6.4: "preserving one linked movement operation"). Every non-transfer
    -- reason leaves this NULL.
    transfer_group_id UUID,
    event_reference_id UUID,
    audit_reference_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_stock_movement_balance FOREIGN KEY (consumable_stock_balance_id, organization_id)
        REFERENCES consumable_stock_balance (id, organization_id),
    CONSTRAINT ck_stock_movement_reason CHECK (reason IN (
        'RECEIPT', 'TRANSFER', 'EVENT_ISSUE', 'EVENT_RETURN', 'CONSUMPTION', 'AUDIT_ADJUSTMENT', 'MANUAL_ADJUSTMENT'
    )),
    CONSTRAINT ck_stock_movement_quantity_delta_nonzero CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_movement_resulting_quantity_non_negative CHECK (resulting_quantity >= 0),
    CONSTRAINT ck_stock_movement_transfer_group_scope CHECK (
        (reason = 'TRANSFER' AND transfer_group_id IS NOT NULL)
        OR (reason <> 'TRANSFER' AND transfer_group_id IS NULL)
    )
);

CREATE INDEX ix_stock_movement_balance ON stock_movement (consumable_stock_balance_id, occurred_at DESC);
CREATE INDEX ix_stock_movement_organization ON stock_movement (organization_id, occurred_at DESC);
CREATE INDEX ix_stock_movement_transfer_group ON stock_movement (transfer_group_id) WHERE transfer_group_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Immutability (specification section 6.4 "immutable"; section 25; AGENTS.md "immutable completed
-- manifests/audit observations"). activity_log/asset_state_history rely only on the Java entity
-- exposing no setters; stock_movement goes further and is a stronger, database-level backstop,
-- because the ledger's append-only property is exactly what the balance-reconciliation guarantee
-- (balance.quantity == SUM(stock_movement.quantity_delta)) depends on. Any UPDATE or DELETE is
-- rejected outright, regardless of which role or code path attempts it.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fn_reject_stock_movement_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'stock_movement rows are immutable and append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_stock_movement_reject_update
    BEFORE UPDATE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION fn_reject_stock_movement_mutation();

CREATE TRIGGER tr_stock_movement_reject_delete
    BEFORE DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION fn_reject_stock_movement_mutation();
