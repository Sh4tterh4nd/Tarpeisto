-- Phase 8: immutable checkout facts, custody, and the audit-task graph foundation.
ALTER TABLE event_booking ALTER COLUMN status TYPE VARCHAR(30);
CREATE TABLE checkout_manifest (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    event_booking_id UUID NOT NULL,
    reservation_revision_id UUID NOT NULL,
    checkout_mutation_id UUID NOT NULL,
    checkout_fingerprint VARCHAR(64) NOT NULL,
    booking_snapshot JSONB NOT NULL,
    checked_out_by_user_id UUID NOT NULL REFERENCES app_user (id),
    checked_out_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_checkout_manifest_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_checkout_manifest_booking UNIQUE (organization_id, event_booking_id),
    CONSTRAINT uk_checkout_manifest_mutation UNIQUE (organization_id, checkout_mutation_id),
    CONSTRAINT fk_checkout_manifest_booking FOREIGN KEY (event_booking_id, organization_id)
        REFERENCES event_booking (id, organization_id),
    CONSTRAINT fk_checkout_manifest_revision FOREIGN KEY (reservation_revision_id, organization_id)
        REFERENCES event_booking_reservation_revision (id, organization_id),
    CONSTRAINT ck_checkout_manifest_snapshot CHECK (jsonb_typeof(booking_snapshot) = 'object')
);

CREATE TABLE checkout_manifest_asset (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    checkout_manifest_id UUID NOT NULL,
    physical_asset_id UUID NOT NULL,
    source_booking_line_id UUID,
    container_asset_id UUID,
    physical_parent_container_asset_id UUID,
    is_container BOOLEAN NOT NULL,
    asset_snapshot JSONB NOT NULL,
    container_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    returned_at TIMESTAMPTZ,
    returned_by_user_id UUID REFERENCES app_user (id),
    return_mutation_id UUID,
    audit_released_at TIMESTAMPTZ,
    CONSTRAINT ck_checkout_manifest_asset_return_fact CHECK ((returned_at IS NULL AND returned_by_user_id IS NULL AND return_mutation_id IS NULL) OR (returned_at IS NOT NULL AND returned_by_user_id IS NOT NULL AND return_mutation_id IS NOT NULL)),
    CONSTRAINT ck_checkout_manifest_asset_release CHECK (audit_released_at IS NULL OR returned_at IS NOT NULL),
    CONSTRAINT fk_checkout_manifest_asset_manifest FOREIGN KEY (checkout_manifest_id, organization_id)
        REFERENCES checkout_manifest (id, organization_id),
    CONSTRAINT fk_checkout_manifest_asset_asset FOREIGN KEY (physical_asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_checkout_manifest_asset_line FOREIGN KEY (source_booking_line_id, organization_id) REFERENCES event_booking_line (id, organization_id),
    CONSTRAINT fk_checkout_manifest_asset_context FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_checkout_manifest_asset_parent FOREIGN KEY (physical_parent_container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT uk_checkout_manifest_asset UNIQUE (checkout_manifest_id, physical_asset_id),
    CONSTRAINT ck_checkout_manifest_asset_snapshot CHECK (jsonb_typeof(asset_snapshot) = 'object' AND jsonb_typeof(container_snapshot) = 'object')
);
CREATE UNIQUE INDEX ix_checkout_manifest_asset_custody ON checkout_manifest_asset (organization_id, physical_asset_id) WHERE audit_released_at IS NULL;

CREATE TABLE checkout_manifest_consumable (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    checkout_manifest_id UUID NOT NULL,
    consumable_stock_balance_id UUID NOT NULL,
    source_booking_line_id UUID,
    container_asset_id UUID,
    quantity NUMERIC NOT NULL,
    returned_quantity NUMERIC NOT NULL DEFAULT 0,
    accounted_at TIMESTAMPTZ,
    semantics VARCHAR(30) NOT NULL,
    consumable_snapshot JSONB NOT NULL,
    CONSTRAINT fk_checkout_manifest_consumable_manifest FOREIGN KEY (checkout_manifest_id, organization_id)
        REFERENCES checkout_manifest (id, organization_id),
    CONSTRAINT fk_checkout_manifest_consumable_stock FOREIGN KEY (consumable_stock_balance_id, organization_id)
        REFERENCES consumable_stock_balance (id, organization_id),
    CONSTRAINT fk_checkout_manifest_consumable_line FOREIGN KEY (source_booking_line_id, organization_id) REFERENCES event_booking_line (id, organization_id),
    CONSTRAINT fk_checkout_manifest_consumable_context FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT ck_checkout_manifest_consumable_semantics CHECK (semantics IN ('SEPARATELY_ISSUED', 'CARRIED_IN_CONTAINER')),
    CONSTRAINT ck_checkout_manifest_consumable_quantity CHECK (quantity > 0 AND returned_quantity >= 0 AND returned_quantity <= quantity AND scale(quantity) <= 3 AND scale(returned_quantity) <= 3),
    CONSTRAINT ck_checkout_manifest_consumable_snapshot CHECK (jsonb_typeof(consumable_snapshot) = 'object')
);

CREATE TABLE checkout_manifest_override (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    checkout_manifest_id UUID NOT NULL,
    reason TEXT NOT NULL,
    recorded_by_user_id UUID NOT NULL REFERENCES app_user (id),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_checkout_manifest_override_manifest FOREIGN KEY (checkout_manifest_id, organization_id)
        REFERENCES checkout_manifest (id, organization_id),
    CONSTRAINT ck_checkout_manifest_override_reason CHECK (length(trim(reason)) > 0)
);

CREATE TABLE audit_batch (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    event_booking_id UUID NOT NULL,
    checkout_manifest_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_audit_batch_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_audit_batch_event UNIQUE (organization_id, event_booking_id),
    CONSTRAINT fk_audit_batch_booking FOREIGN KEY (event_booking_id, organization_id) REFERENCES event_booking (id, organization_id),
    CONSTRAINT fk_audit_batch_manifest FOREIGN KEY (checkout_manifest_id, organization_id) REFERENCES checkout_manifest (id, organization_id)
);

CREATE TABLE audit_task (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    audit_batch_id UUID NOT NULL,
    container_asset_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_audit_task_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_audit_task_container UNIQUE (audit_batch_id, container_asset_id),
    CONSTRAINT fk_audit_task_batch FOREIGN KEY (audit_batch_id, organization_id) REFERENCES audit_batch (id, organization_id),
    CONSTRAINT fk_audit_task_container FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT ck_audit_task_state CHECK (state IN ('READY', 'BLOCKED', 'COMPLETED'))
);
CREATE TABLE audit_task_dependency (
    organization_id UUID NOT NULL REFERENCES organization (id),
    audit_task_id UUID NOT NULL,
    depends_on_audit_task_id UUID NOT NULL,
    PRIMARY KEY (audit_task_id, depends_on_audit_task_id),
    CONSTRAINT fk_audit_task_dependency_task FOREIGN KEY (audit_task_id, organization_id) REFERENCES audit_task (id, organization_id),
    CONSTRAINT fk_audit_task_dependency_parent FOREIGN KEY (depends_on_audit_task_id, organization_id) REFERENCES audit_task (id, organization_id),
    CONSTRAINT ck_audit_task_dependency_distinct CHECK (audit_task_id <> depends_on_audit_task_id)
);

CREATE FUNCTION fn_reject_checkout_manifest_mutation() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'checkout manifest facts are immutable' USING ERRCODE = '23514'; END;
$$ LANGUAGE plpgsql;
CREATE TABLE checkout_return_operation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    checkout_manifest_id UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    recorded_by_user_id UUID NOT NULL REFERENCES app_user(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_checkout_return_operation_manifest FOREIGN KEY (checkout_manifest_id, organization_id) REFERENCES checkout_manifest(id, organization_id)
);
CREATE TRIGGER tr_checkout_return_operation_immutable BEFORE UPDATE OR DELETE ON checkout_return_operation FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_checkout_manifest_immutable BEFORE UPDATE OR DELETE ON checkout_manifest FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_checkout_manifest_override_immutable BEFORE UPDATE OR DELETE ON checkout_manifest_override FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_checkout_manifest_asset_immutable BEFORE DELETE ON checkout_manifest_asset FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_checkout_manifest_consumable_immutable BEFORE DELETE ON checkout_manifest_consumable FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE FUNCTION fn_reject_checkout_manifest_asset_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.checkout_manifest_id <> OLD.checkout_manifest_id
       OR NEW.physical_asset_id <> OLD.physical_asset_id OR NEW.source_booking_line_id IS DISTINCT FROM OLD.source_booking_line_id
       OR NEW.container_asset_id IS DISTINCT FROM OLD.container_asset_id OR NEW.asset_snapshot <> OLD.asset_snapshot
       OR NEW.physical_parent_container_asset_id IS DISTINCT FROM OLD.physical_parent_container_asset_id OR NEW.is_container <> OLD.is_container
       OR NEW.container_snapshot <> OLD.container_snapshot
       OR (OLD.returned_at IS NOT NULL AND (NEW.returned_at IS DISTINCT FROM OLD.returned_at OR NEW.returned_by_user_id IS DISTINCT FROM OLD.returned_by_user_id OR NEW.return_mutation_id IS DISTINCT FROM OLD.return_mutation_id))
       OR (OLD.audit_released_at IS NOT NULL AND NEW.audit_released_at IS DISTINCT FROM OLD.audit_released_at) THEN
        RAISE EXCEPTION 'checkout manifest asset facts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_checkout_manifest_asset_return_only BEFORE UPDATE ON checkout_manifest_asset FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_asset_fact_mutation();
CREATE FUNCTION fn_reject_checkout_manifest_consumable_fact_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.organization_id <> OLD.organization_id OR NEW.checkout_manifest_id <> OLD.checkout_manifest_id
       OR NEW.consumable_stock_balance_id <> OLD.consumable_stock_balance_id OR NEW.source_booking_line_id IS DISTINCT FROM OLD.source_booking_line_id
       OR NEW.container_asset_id IS DISTINCT FROM OLD.container_asset_id OR NEW.quantity <> OLD.quantity
       OR NEW.semantics <> OLD.semantics OR NEW.consumable_snapshot <> OLD.consumable_snapshot
       OR NEW.returned_quantity < OLD.returned_quantity
       OR (OLD.accounted_at IS NOT NULL AND (NEW.accounted_at IS DISTINCT FROM OLD.accounted_at OR NEW.returned_quantity <> OLD.returned_quantity)) THEN
        RAISE EXCEPTION 'checkout manifest consumable facts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_checkout_manifest_consumable_return_only BEFORE UPDATE ON checkout_manifest_consumable FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_consumable_fact_mutation();
