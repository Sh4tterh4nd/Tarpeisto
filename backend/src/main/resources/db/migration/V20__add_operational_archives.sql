ALTER TABLE consumable_stock_balance ADD COLUMN archived_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_archived_stock_zero CHECK (archived_at IS NULL OR quantity = 0);
ALTER TABLE app_user ADD COLUMN archived_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_archived_user_disabled CHECK (archived_at IS NULL OR NOT enabled);
ALTER TABLE event_booking ADD COLUMN archived_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_archived_event_completed CHECK (archived_at IS NULL OR status = 'COMPLETED');

CREATE TABLE container_audit_archive (
    container_audit_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_audit_archive_audit FOREIGN KEY (container_audit_id, organization_id)
        REFERENCES container_audit(id, organization_id),
    CONSTRAINT ck_audit_archive_version CHECK (version >= 0)
);

CREATE FUNCTION fn_reject_archived_balance_quantity_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.archived_at IS NOT NULL AND NEW.quantity IS DISTINCT FROM OLD.quantity THEN
        RAISE EXCEPTION 'restore archived stock before changing quantity' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_archived_balance_quantity BEFORE UPDATE ON consumable_stock_balance
    FOR EACH ROW EXECUTE FUNCTION fn_reject_archived_balance_quantity_mutation();

CREATE FUNCTION fn_reject_archived_user_identity_link() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_user WHERE id = NEW.user_id AND archived_at IS NOT NULL) THEN
        RAISE EXCEPTION 'restore archived user before linking identity' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_archived_user_identity_link BEFORE INSERT OR UPDATE ON external_identity
    FOR EACH ROW EXECUTE FUNCTION fn_reject_archived_user_identity_link();
