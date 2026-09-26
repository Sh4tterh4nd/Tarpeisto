-- Phase 10 is deliberately additive. Completed manifests, audit observations, findings and scans
-- remain immutable; review decisions and operational projections live in their own tables.

ALTER TABLE physical_asset
    ADD COLUMN sealable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN seal_state VARCHAR(20) NOT NULL DEFAULT 'UNSEALED',
    ADD COLUMN seal_verified_at TIMESTAMPTZ,
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN last_verified_audit_id UUID,
    ADD COLUMN replaces_asset_id UUID,
    ADD CONSTRAINT fk_physical_asset_replaces FOREIGN KEY (replaces_asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    ADD CONSTRAINT ck_physical_asset_seal_state
        CHECK (seal_state IN ('UNSEALED', 'APPLIED', 'BROKEN', 'VERIFIED', 'INVALIDATED'));

-- V13 predates the review projection and did not need this composite candidate key.
ALTER TABLE audit_finding
    ADD CONSTRAINT uk_audit_finding_id_organization UNIQUE (id, organization_id);
ALTER TABLE checkout_manifest_asset
    ADD CONSTRAINT uk_checkout_manifest_asset_id_organization UNIQUE (id, organization_id);

-- Attempts are separate from the historical task graph.  A task has at most one current attempt,
-- while old completed attempts retain their scans and completion summary unchanged.
ALTER TABLE container_audit DROP CONSTRAINT uk_container_audit_task;
ALTER TABLE container_audit
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN current_attempt BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT uk_container_audit_task_attempt UNIQUE (audit_task_id, attempt_number),
    ADD CONSTRAINT ck_container_audit_attempt_number CHECK (attempt_number > 0);
CREATE UNIQUE INDEX ux_container_audit_current_attempt ON container_audit (audit_task_id) WHERE current_attempt;

ALTER TABLE physical_asset
    ADD CONSTRAINT fk_physical_asset_last_verified_audit FOREIGN KEY (last_verified_audit_id, organization_id)
        REFERENCES container_audit (id, organization_id),
    ADD CONSTRAINT ck_physical_asset_verification CHECK ((last_verified_at IS NULL) = (last_verified_audit_id IS NULL));

CREATE FUNCTION fn_reject_replacement_predecessor_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.replaces_asset_id IS NOT NULL AND NEW.replaces_asset_id IS DISTINCT FROM OLD.replaces_asset_id THEN
        RAISE EXCEPTION 'replacement predecessors are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_physical_asset_replacement_immutable BEFORE UPDATE ON physical_asset
    FOR EACH ROW EXECUTE FUNCTION fn_reject_replacement_predecessor_mutation();

-- Old scans stay immutable. Only current attempts participate in batch-wide scan ownership.
DROP INDEX ux_audit_scan_batch_active_asset;
CREATE FUNCTION fn_reject_duplicate_current_audit_scan() RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM audit_batch WHERE id = NEW.audit_batch_id FOR UPDATE;
    IF NEW.undone_at IS NULL AND EXISTS (
        SELECT 1 FROM audit_scan scan JOIN container_audit audit ON audit.id = scan.container_audit_id
        WHERE scan.audit_batch_id = NEW.audit_batch_id AND scan.physical_asset_id = NEW.physical_asset_id
          AND scan.undone_at IS NULL AND scan.id <> NEW.id AND audit.current_attempt
    ) THEN
        RAISE EXCEPTION 'an asset can only count in one current audit attempt per batch' USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_audit_scan_current_batch_unique BEFORE INSERT OR UPDATE ON audit_scan
    FOR EACH ROW EXECUTE FUNCTION fn_reject_duplicate_current_audit_scan();

-- A completed attempt stays immutable except for being retired from the current-attempt projection
-- before a fresh attempt is started after an explicit seal break/reopen.
DROP TRIGGER IF EXISTS tr_container_audit_completion_only ON container_audit;
CREATE FUNCTION fn_reject_completed_audit_attempt_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state = 'COMPLETED' AND TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'completed audits are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'COMPLETED' AND TG_OP = 'UPDATE'
       AND NOT (OLD.current_attempt = TRUE AND NEW.current_attempt = FALSE
                AND (to_jsonb(OLD) - 'current_attempt') = (to_jsonb(NEW) - 'current_attempt')) THEN
        RAISE EXCEPTION 'completed audits are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_container_audit_attempt_completion_only BEFORE UPDATE OR DELETE ON container_audit
    FOR EACH ROW EXECUTE FUNCTION fn_reject_completed_audit_attempt_mutation();

CREATE TABLE audit_finding_resolution (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    audit_finding_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    operation_id UUID NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    note TEXT,
    target_asset_id UUID,
    target_container_asset_id UUID,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    resolved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_audit_finding_resolution_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_audit_finding_resolution_finding UNIQUE (audit_finding_id),
    CONSTRAINT uk_audit_finding_resolution_operation UNIQUE (organization_id, operation_id),
    CONSTRAINT fk_audit_finding_resolution_finding FOREIGN KEY (audit_finding_id, organization_id)
        REFERENCES audit_finding(id, organization_id),
    CONSTRAINT fk_audit_finding_resolution_target_asset FOREIGN KEY (target_asset_id, organization_id)
        REFERENCES physical_asset(id, organization_id),
    CONSTRAINT fk_audit_finding_resolution_target_container FOREIGN KEY (target_container_asset_id, organization_id)
        REFERENCES physical_asset(id, organization_id),
    CONSTRAINT ck_audit_finding_resolution_action CHECK (action IN
        ('FOUND_AND_RETURNED', 'MOVE_TO_CORRECT_CONTAINER', 'REASSIGN_CURRENT_CONTAINER',
         'MARK_LOST', 'MARK_DAMAGED', 'CREATE_REPAIR', 'MARK_DESTROYED', 'REPLACE_LABEL', 'DISMISS')),
    CONSTRAINT ck_audit_finding_resolution_dismiss_note CHECK (action <> 'DISMISS' OR length(trim(coalesce(note, ''))) > 0)
);

CREATE TABLE asset_repair (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    asset_id UUID NOT NULL,
    source_finding_id UUID,
    reference_or_description TEXT NOT NULL,
    opened_by_user_id UUID NOT NULL REFERENCES app_user(id),
    opened_at TIMESTAMPTZ NOT NULL,
    closed_by_user_id UUID REFERENCES app_user(id),
    closed_at TIMESTAMPTZ,
    resulting_condition VARCHAR(20),
    CONSTRAINT fk_asset_repair_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset(id, organization_id),
    CONSTRAINT fk_asset_repair_finding FOREIGN KEY (source_finding_id, organization_id)
        REFERENCES audit_finding(id, organization_id),
    CONSTRAINT ck_asset_repair_reference CHECK (length(trim(reference_or_description)) > 0),
    CONSTRAINT ck_asset_repair_close CHECK ((closed_at IS NULL AND closed_by_user_id IS NULL AND resulting_condition IS NULL)
        OR (closed_at IS NOT NULL AND closed_by_user_id IS NOT NULL AND resulting_condition IN ('GOOD', 'DAMAGED')))
);
CREATE UNIQUE INDEX ux_asset_repair_one_open ON asset_repair(asset_id) WHERE closed_at IS NULL;

CREATE TABLE asset_seal_history (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    asset_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    source_audit_id UUID,
    note TEXT,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_asset_seal_history_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset(id, organization_id),
    CONSTRAINT fk_asset_seal_history_audit FOREIGN KEY (source_audit_id, organization_id)
        REFERENCES container_audit(id, organization_id),
    CONSTRAINT ck_asset_seal_history_action CHECK (action IN ('APPLIED', 'BROKEN', 'VERIFIED', 'INVALIDATED'))
);

CREATE TABLE asset_verification_history (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    asset_id UUID NOT NULL,
    container_audit_id UUID NOT NULL,
    verification_state VARCHAR(20) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    CONSTRAINT fk_asset_verification_history_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset(id, organization_id),
    CONSTRAINT fk_asset_verification_history_audit FOREIGN KEY (container_audit_id, organization_id)
        REFERENCES container_audit(id, organization_id),
    CONSTRAINT ck_asset_verification_state CHECK (verification_state IN ('VERIFIED', 'INVALIDATED'))
);

-- Formal accounting is explicitly distinct from physical return. It never writes returned_at.
-- V12 required a physical return before audit release. A Phase-10 loss/destruction decision is an
-- equally terminal custody fact, but it must be backed by its own immutable accounting record.
ALTER TABLE checkout_manifest_asset DROP CONSTRAINT ck_checkout_manifest_asset_release;
CREATE TABLE manifest_asset_accounting (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    checkout_manifest_asset_id UUID NOT NULL,
    audit_finding_resolution_id UUID NOT NULL,
    accounting_state VARCHAR(20) NOT NULL,
    accounted_by_user_id UUID NOT NULL REFERENCES app_user(id),
    accounted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_manifest_asset_accounting_manifest_asset UNIQUE (checkout_manifest_asset_id),
    CONSTRAINT fk_manifest_asset_accounting_manifest_asset FOREIGN KEY (checkout_manifest_asset_id, organization_id)
        REFERENCES checkout_manifest_asset(id, organization_id),
    CONSTRAINT fk_manifest_asset_accounting_resolution FOREIGN KEY (audit_finding_resolution_id, organization_id)
        REFERENCES audit_finding_resolution(id, organization_id),
    CONSTRAINT ck_manifest_asset_accounting_state CHECK (accounting_state IN ('LOST', 'DESTROYED'))
);

CREATE FUNCTION fn_reject_inconsistent_manifest_accounting() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM checkout_manifest_asset item
        JOIN audit_finding_resolution resolution ON resolution.id = NEW.audit_finding_resolution_id
        JOIN audit_finding finding ON finding.id = resolution.audit_finding_id
        WHERE item.id = NEW.checkout_manifest_asset_id
          AND item.organization_id = NEW.organization_id AND resolution.organization_id = NEW.organization_id
          AND finding.physical_asset_id = item.physical_asset_id
          AND resolution.action = CASE NEW.accounting_state WHEN 'LOST' THEN 'MARK_LOST' ELSE 'MARK_DESTROYED' END
    ) THEN
        RAISE EXCEPTION 'formal accounting must match the resolution and exact manifest asset' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_manifest_asset_accounting_consistent BEFORE INSERT ON manifest_asset_accounting
    FOR EACH ROW EXECUTE FUNCTION fn_reject_inconsistent_manifest_accounting();

CREATE FUNCTION fn_reject_unaccounted_manifest_release() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.audit_released_at IS NOT NULL AND NEW.returned_at IS NULL
       AND NOT EXISTS (SELECT 1 FROM manifest_asset_accounting accounting
                       WHERE accounting.checkout_manifest_asset_id = NEW.id) THEN
        RAISE EXCEPTION 'a non-physical manifest release requires formal accounting' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_checkout_manifest_asset_formal_release BEFORE INSERT OR UPDATE ON checkout_manifest_asset
    FOR EACH ROW EXECUTE FUNCTION fn_reject_unaccounted_manifest_release();

CREATE FUNCTION fn_reject_review_history_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'review and history records are append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_audit_finding_resolution_immutable BEFORE UPDATE OR DELETE ON audit_finding_resolution
    FOR EACH ROW EXECUTE FUNCTION fn_reject_review_history_mutation();
CREATE TRIGGER tr_asset_seal_history_immutable BEFORE UPDATE OR DELETE ON asset_seal_history
    FOR EACH ROW EXECUTE FUNCTION fn_reject_review_history_mutation();
CREATE TRIGGER tr_asset_verification_history_immutable BEFORE UPDATE OR DELETE ON asset_verification_history
    FOR EACH ROW EXECUTE FUNCTION fn_reject_review_history_mutation();
CREATE TRIGGER tr_manifest_asset_accounting_immutable BEFORE UPDATE OR DELETE ON manifest_asset_accounting
    FOR EACH ROW EXECUTE FUNCTION fn_reject_review_history_mutation();
CREATE FUNCTION fn_reject_closed_repair_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.closed_at IS NOT NULL
       OR (to_jsonb(OLD) - ARRAY['closed_at', 'closed_by_user_id', 'resulting_condition'])
          IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['closed_at', 'closed_by_user_id', 'resulting_condition']) THEN
        RAISE EXCEPTION 'repair history is immutable once recorded or closed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_asset_repair_closed_immutable BEFORE UPDATE OR DELETE ON asset_repair
    FOR EACH ROW EXECUTE FUNCTION fn_reject_closed_repair_mutation();
