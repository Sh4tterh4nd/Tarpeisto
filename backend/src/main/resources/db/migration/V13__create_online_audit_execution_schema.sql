-- Phase 9.1: online audit execution. V12's return/task foundation is intentionally untouched.
CREATE TABLE container_audit (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    audit_batch_id UUID NOT NULL,
    audit_task_id UUID NOT NULL,
    container_asset_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL,
    started_by_user_id UUID NOT NULL REFERENCES app_user(id),
    started_at TIMESTAMPTZ NOT NULL,
    completed_by_user_id UUID REFERENCES app_user(id),
    completed_at TIMESTAMPTZ,
    completion_outcome VARCHAR(20),
    final_container_code VARCHAR(16),
    seal_confirmed BOOLEAN,
    CONSTRAINT uk_container_audit_task UNIQUE (audit_task_id),
    CONSTRAINT uk_container_audit_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_container_audit_id_batch_organization UNIQUE (id, audit_batch_id, organization_id),
    CONSTRAINT fk_container_audit_batch FOREIGN KEY (audit_batch_id, organization_id) REFERENCES audit_batch(id, organization_id),
    CONSTRAINT fk_container_audit_task FOREIGN KEY (audit_task_id, organization_id) REFERENCES audit_task(id, organization_id),
    CONSTRAINT fk_container_audit_container FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset(id, organization_id),
    CONSTRAINT ck_container_audit_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_container_audit_completion CHECK ((state = 'IN_PROGRESS' AND completed_at IS NULL AND completion_outcome IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND completion_outcome IN ('CLEAN', 'FINDINGS')))
);

ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_audit
    FOREIGN KEY (audit_reference_id, organization_id) REFERENCES container_audit(id, organization_id);

CREATE TABLE audit_expected_requirement (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    container_audit_id UUID NOT NULL,
    source_packing_requirement_id UUID,
    requirement_type VARCHAR(30) NOT NULL,
    asset_model_id UUID,
    specific_asset_id UUID,
    required_quantity NUMERIC,
    display_order INTEGER NOT NULL,
    snapshot JSONB NOT NULL,
    CONSTRAINT uk_audit_expected_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_audit_expected_id_audit_organization UNIQUE (id, container_audit_id, organization_id),
    CONSTRAINT fk_audit_expected_audit FOREIGN KEY (container_audit_id, organization_id) REFERENCES container_audit(id, organization_id),
    CONSTRAINT fk_audit_expected_source FOREIGN KEY (source_packing_requirement_id, organization_id) REFERENCES packing_requirement(id, organization_id),
    CONSTRAINT fk_audit_expected_model FOREIGN KEY (asset_model_id, organization_id) REFERENCES asset_model(id, organization_id),
    CONSTRAINT fk_audit_expected_asset FOREIGN KEY (specific_asset_id, organization_id) REFERENCES physical_asset(id, organization_id),
    CONSTRAINT ck_audit_expected_quantity CHECK ((requirement_type = 'SPECIFIC_ASSET' AND specific_asset_id IS NOT NULL AND asset_model_id IS NULL AND required_quantity = 1)
        OR (requirement_type IN ('MODEL_QUANTITY', 'CONSUMABLE_QUANTITY') AND specific_asset_id IS NULL AND asset_model_id IS NOT NULL AND required_quantity > 0)),
    CONSTRAINT ck_audit_expected_snapshot CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT ck_audit_expected_type CHECK (requirement_type IN ('SPECIFIC_ASSET', 'MODEL_QUANTITY', 'CONSUMABLE_QUANTITY'))
);

CREATE TABLE audit_scan (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    container_audit_id UUID NOT NULL,
    audit_batch_id UUID NOT NULL,
    physical_asset_id UUID NOT NULL,
    client_operation_id UUID NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    context_snapshot JSONB NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL,
    scanned_by_user_id UUID NOT NULL REFERENCES app_user(id),
    undone_at TIMESTAMPTZ,
    undone_by_user_id UUID REFERENCES app_user(id),
    CONSTRAINT fk_audit_scan_audit FOREIGN KEY (container_audit_id, organization_id) REFERENCES container_audit(id, organization_id),
    CONSTRAINT fk_audit_scan_batch_audit FOREIGN KEY (container_audit_id, audit_batch_id, organization_id) REFERENCES container_audit(id, audit_batch_id, organization_id),
    CONSTRAINT fk_audit_scan_asset FOREIGN KEY (physical_asset_id, organization_id) REFERENCES physical_asset(id, organization_id),
    CONSTRAINT uk_audit_scan_operation UNIQUE (container_audit_id, client_operation_id),
    CONSTRAINT ck_audit_scan_outcome CHECK (outcome IN ('EXPECTED_EXACT', 'EXPECTED_MODEL', 'DUPLICATE', 'EXTRA', 'MISPLACED')),
    CONSTRAINT ck_audit_scan_context CHECK (jsonb_typeof(context_snapshot) = 'object'),
    CONSTRAINT ck_audit_scan_undo CHECK ((undone_at IS NULL AND undone_by_user_id IS NULL) OR (undone_at IS NOT NULL AND undone_by_user_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_audit_scan_active_asset ON audit_scan(container_audit_id, physical_asset_id) WHERE undone_at IS NULL;
CREATE UNIQUE INDEX ux_audit_scan_batch_active_asset ON audit_scan(audit_batch_id, physical_asset_id) WHERE undone_at IS NULL;

CREATE TABLE audit_consumable_observation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    container_audit_id UUID NOT NULL,
    audit_expected_requirement_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    observed_quantity NUMERIC,
    client_operation_id UUID NOT NULL,
    recorded_by_user_id UUID NOT NULL REFERENCES app_user(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_consumable_audit FOREIGN KEY (container_audit_id, organization_id) REFERENCES container_audit(id, organization_id),
    CONSTRAINT fk_audit_consumable_expected FOREIGN KEY (audit_expected_requirement_id, organization_id) REFERENCES audit_expected_requirement(id, organization_id),
    CONSTRAINT fk_audit_consumable_expected_audit FOREIGN KEY (audit_expected_requirement_id, container_audit_id, organization_id) REFERENCES audit_expected_requirement(id, container_audit_id, organization_id),
    CONSTRAINT uk_audit_consumable_operation UNIQUE (container_audit_id, client_operation_id),
    CONSTRAINT uk_audit_consumable_requirement UNIQUE (container_audit_id, audit_expected_requirement_id),
    CONSTRAINT ck_audit_consumable_status CHECK (status IN ('CONFIRMED', 'OBSERVED', 'MISSING_LOW')),
    CONSTRAINT ck_audit_consumable_quantity CHECK ((status <> 'OBSERVED' OR observed_quantity IS NOT NULL) AND (observed_quantity IS NULL OR (observed_quantity >= 0 AND scale(trim_scale(observed_quantity)) <= 3)))
);

CREATE TABLE audit_finding (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    container_audit_id UUID,
    physical_asset_id UUID,
    finding_type VARCHAR(30) NOT NULL,
    note TEXT,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    recorded_by_user_id UUID NOT NULL REFERENCES app_user(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_finding_audit FOREIGN KEY (container_audit_id, organization_id) REFERENCES container_audit(id, organization_id),
    CONSTRAINT fk_audit_finding_asset FOREIGN KEY (physical_asset_id, organization_id) REFERENCES physical_asset(id, organization_id),
    CONSTRAINT ck_audit_finding_type CHECK (finding_type IN ('MISSING', 'DAMAGED', 'UNEXPECTED', 'MISPLACED', 'UNREADABLE_LABEL', 'UNKNOWN_CODE')),
    CONSTRAINT ck_audit_finding_detail CHECK (jsonb_typeof(detail) = 'object')
);

CREATE TABLE audit_operation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    container_audit_id UUID NOT NULL,
    client_operation_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_operation_audit FOREIGN KEY (container_audit_id, organization_id) REFERENCES container_audit(id, organization_id),
    CONSTRAINT uk_audit_operation UNIQUE (container_audit_id, client_operation_id)
);

CREATE FUNCTION fn_reject_completed_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state = 'COMPLETED' THEN
        RAISE EXCEPTION 'completed audits are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_container_audit_completion_only BEFORE UPDATE OR DELETE ON container_audit FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_mutation();
CREATE TRIGGER tr_audit_expected_immutable BEFORE UPDATE OR DELETE ON audit_expected_requirement FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_audit_finding_immutable BEFORE UPDATE OR DELETE ON audit_finding FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE TRIGGER tr_audit_operation_immutable BEFORE UPDATE OR DELETE ON audit_operation FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
CREATE FUNCTION fn_reject_completed_audit_observation_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM container_audit WHERE id = COALESCE(NEW.container_audit_id, OLD.container_audit_id) AND state = 'COMPLETED')
       OR (TG_OP = 'UPDATE' AND EXISTS (SELECT 1 FROM container_audit WHERE id = OLD.container_audit_id AND state = 'COMPLETED')) THEN
        RAISE EXCEPTION 'completed audit observations are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_audit_scan_completed_immutable BEFORE INSERT OR UPDATE OR DELETE ON audit_scan FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_observation_mutation();
CREATE TRIGGER tr_audit_consumable_completed_immutable BEFORE INSERT OR UPDATE OR DELETE ON audit_consumable_observation FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_observation_mutation();
CREATE TRIGGER tr_audit_expected_completed_immutable BEFORE INSERT ON audit_expected_requirement FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_observation_mutation();
CREATE TRIGGER tr_audit_finding_completed_immutable BEFORE INSERT ON audit_finding FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_observation_mutation();
CREATE TRIGGER tr_audit_operation_completed_immutable BEFORE INSERT ON audit_operation FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_observation_mutation();
