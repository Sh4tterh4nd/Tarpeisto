ALTER TABLE audit_finding ADD COLUMN source_operation_id UUID;
CREATE UNIQUE INDEX uk_audit_finding_source_operation ON audit_finding(container_audit_id, source_operation_id)
    WHERE source_operation_id IS NOT NULL;
ALTER TABLE audit_finding ADD CONSTRAINT uk_audit_finding_audit_organization UNIQUE(id, container_audit_id, organization_id);
ALTER TABLE media_object ADD COLUMN container_audit_id UUID;
ALTER TABLE media_object ADD COLUMN audit_finding_id UUID;
ALTER TABLE media_object ADD COLUMN upload_operation_id UUID;
ALTER TABLE media_object ADD CONSTRAINT fk_media_audit FOREIGN KEY(container_audit_id, organization_id)
    REFERENCES container_audit(id, organization_id);
ALTER TABLE media_object ADD CONSTRAINT fk_media_finding_audit FOREIGN KEY(audit_finding_id, container_audit_id, organization_id)
    REFERENCES audit_finding(id, container_audit_id, organization_id);
ALTER TABLE media_object ADD CONSTRAINT uk_media_upload_operation UNIQUE(organization_id, upload_operation_id);
ALTER TABLE media_object DROP CONSTRAINT ck_media_object_owner_and_purpose;
ALTER TABLE media_object ADD CONSTRAINT ck_media_object_owner_and_purpose CHECK (
    (purpose = 'MODEL_REFERENCE' AND asset_model_id IS NOT NULL AND asset_id IS NULL AND caption IS NULL AND display_order = 0
        AND container_audit_id IS NULL AND audit_finding_id IS NULL AND upload_operation_id IS NULL)
    OR (purpose = 'ASSET_REFERENCE' AND asset_id IS NOT NULL AND asset_model_id IS NULL AND caption IS NULL AND display_order = 0
        AND container_audit_id IS NULL AND audit_finding_id IS NULL AND upload_operation_id IS NULL)
    OR (purpose = 'CONTAINER_LAYOUT' AND asset_id IS NOT NULL AND asset_model_id IS NULL
        AND container_audit_id IS NULL AND audit_finding_id IS NULL AND upload_operation_id IS NULL)
    OR (purpose = 'AUDIT_EVIDENCE' AND asset_id IS NULL AND asset_model_id IS NULL AND container_audit_id IS NOT NULL
        AND audit_finding_id IS NOT NULL AND upload_operation_id IS NOT NULL AND archived_at IS NULL
        AND NOT cleanup_pending AND NOT primary_image AND caption IS NULL AND display_order = 0)
);
CREATE INDEX ix_media_audit ON media_object(organization_id, container_audit_id, created_at);
CREATE FUNCTION fn_protect_audit_evidence() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP <> 'INSERT' AND OLD.purpose = 'AUDIT_EVIDENCE' THEN
        RAISE EXCEPTION 'audit evidence is immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.purpose = 'AUDIT_EVIDENCE' THEN
        PERFORM 1 FROM organization WHERE id = NEW.organization_id FOR UPDATE;
        PERFORM 1 FROM container_audit WHERE id = NEW.container_audit_id
            AND organization_id = NEW.organization_id FOR UPDATE;
        IF NOT EXISTS (SELECT 1 FROM container_audit WHERE id = NEW.container_audit_id
                AND organization_id = NEW.organization_id AND state = 'IN_PROGRESS' AND current_attempt) THEN
            RAISE EXCEPTION 'completed audit evidence is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_media_audit_evidence BEFORE INSERT OR UPDATE OR DELETE ON media_object
    FOR EACH ROW EXECUTE FUNCTION fn_protect_audit_evidence();
