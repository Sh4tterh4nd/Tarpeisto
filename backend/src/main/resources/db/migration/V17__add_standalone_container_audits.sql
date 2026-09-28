-- A batch is either an event return (both references) or an independently launched container audit.
ALTER TABLE audit_batch ALTER COLUMN event_booking_id DROP NOT NULL;
ALTER TABLE audit_batch ALTER COLUMN checkout_manifest_id DROP NOT NULL;
ALTER TABLE audit_batch ADD CONSTRAINT ck_audit_batch_source
    CHECK ((event_booking_id IS NULL) = (checkout_manifest_id IS NULL));

CREATE TABLE audit_launch_operation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    client_operation_id UUID NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    audit_task_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_audit_launch_operation UNIQUE (organization_id, client_operation_id),
    CONSTRAINT fk_audit_launch_task FOREIGN KEY (audit_task_id, organization_id)
        REFERENCES audit_task(id, organization_id)
);
CREATE TRIGGER tr_audit_launch_operation_immutable BEFORE UPDATE OR DELETE ON audit_launch_operation
    FOR EACH ROW EXECUTE FUNCTION fn_reject_checkout_manifest_mutation();
