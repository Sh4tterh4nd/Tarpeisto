-- Volunteers are real attributable actors without a permanent organization membership.
ALTER TABLE app_user ADD COLUMN temporary_identity BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD CONSTRAINT ck_volunteer_no_credentials CHECK
    (NOT temporary_identity OR (password_hash IS NULL AND email IS NULL));

CREATE TABLE temporary_access_invitation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    booking_id UUID,
    audit_batch_id UUID,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_by_user_id UUID NOT NULL REFERENCES app_user(id),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id UUID REFERENCES app_user(id),
    UNIQUE (id, organization_id),
    FOREIGN KEY (booking_id, organization_id) REFERENCES event_booking(id, organization_id),
    FOREIGN KEY (audit_batch_id, organization_id) REFERENCES audit_batch(id, organization_id),
    CHECK ((booking_id IS NULL) <> (audit_batch_id IS NULL)),
    CHECK (expires_at = issued_at + INTERVAL '24 hours'),
    CHECK ((revoked_at IS NULL) = (revoked_by_user_id IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);
CREATE INDEX ix_invitation_scope ON temporary_access_invitation(organization_id, booking_id, audit_batch_id);
CREATE TABLE volunteer_session (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    invitation_id UUID NOT NULL,
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    redemption_operation_id UUID NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (invitation_id, redemption_operation_id),
    FOREIGN KEY (invitation_id, organization_id) REFERENCES temporary_access_invitation(id, organization_id),
    CHECK (length(trim(display_name)) > 0),
    CHECK (created_at < expires_at)
);
CREATE FUNCTION fn_validate_volunteer_session() RETURNS TRIGGER AS $$
BEGIN
 IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'volunteer grants are immutable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS (SELECT 1 FROM temporary_access_invitation i JOIN app_user u ON u.id=NEW.user_id
     WHERE i.id=NEW.invitation_id AND i.organization_id=NEW.organization_id
     AND i.expires_at=NEW.expires_at AND u.temporary_identity) THEN
   RAISE EXCEPTION 'invalid volunteer grant' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_volunteer_session_validate BEFORE INSERT OR UPDATE OR DELETE ON volunteer_session
 FOR EACH ROW EXECUTE FUNCTION fn_validate_volunteer_session();
CREATE FUNCTION fn_validate_volunteer_invitation() RETURNS TRIGGER AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'invitation history is immutable' USING ERRCODE='23514'; END IF;
 IF (NEW.id,NEW.organization_id,NEW.booking_id,NEW.audit_batch_id,NEW.token_hash,NEW.issued_by_user_id,NEW.issued_at,NEW.expires_at)
 IS DISTINCT FROM (OLD.id,OLD.organization_id,OLD.booking_id,OLD.audit_batch_id,OLD.token_hash,OLD.issued_by_user_id,OLD.issued_at,OLD.expires_at)
 OR (OLD.revoked_at IS NOT NULL AND (NEW.revoked_at,NEW.revoked_by_user_id) IS DISTINCT FROM (OLD.revoked_at,OLD.revoked_by_user_id)) THEN
   RAISE EXCEPTION 'invitation grant is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_invitation_immutable BEFORE UPDATE OR DELETE ON temporary_access_invitation
 FOR EACH ROW EXECUTE FUNCTION fn_validate_volunteer_invitation();
CREATE FUNCTION fn_reject_volunteer_membership() RETURNS TRIGGER AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM app_user WHERE id=NEW.user_id AND temporary_identity) THEN
   RAISE EXCEPTION 'volunteer cannot acquire permanent authentication or membership' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_membership_no_volunteer BEFORE INSERT OR UPDATE ON organization_membership
 FOR EACH ROW EXECUTE FUNCTION fn_reject_volunteer_membership();
CREATE TRIGGER tr_external_identity_no_volunteer BEFORE INSERT OR UPDATE ON external_identity
 FOR EACH ROW EXECUTE FUNCTION fn_reject_volunteer_membership();
CREATE FUNCTION fn_preserve_volunteer_identity() RETURNS TRIGGER AS $$
BEGIN
 IF OLD.temporary_identity AND (NOT NEW.temporary_identity OR NEW.username IS DISTINCT FROM OLD.username
     OR NEW.display_name IS DISTINCT FROM OLD.display_name) THEN
   RAISE EXCEPTION 'volunteer identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_volunteer_identity_immutable BEFORE UPDATE ON app_user
 FOR EACH ROW EXECUTE FUNCTION fn_preserve_volunteer_identity();
