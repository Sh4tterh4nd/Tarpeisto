-- Phase 7: draft events, authoritative reservations, and immutable reservation history.
CREATE TABLE event_booking (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    name VARCHAR(160) NOT NULL,
    creation_mutation_id UUID NOT NULL,
    creation_fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT uk_booking_creation_mutation UNIQUE (organization_id,creation_mutation_id),
    client_text TEXT,
    venue_text TEXT,
    notes TEXT,
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    reservation_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
    current_revision_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_event_booking_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_event_booking_dates CHECK (starts_at < ends_at),
    CONSTRAINT ck_event_booking_status CHECK (status IN ('DRAFT', 'RESERVED', 'CHECKED_OUT', 'RETURNED_AUDITS_PENDING', 'REVIEW_REQUIRED', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_event_booking_dates ON event_booking (organization_id, starts_at, ends_at) WHERE status = 'RESERVED';

CREATE TABLE event_booking_line (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    event_booking_id UUID NOT NULL,
    line_type VARCHAR(20) NOT NULL,
    asset_id UUID,
    consumable_stock_id UUID,
    quantity NUMERIC NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    CONSTRAINT uk_event_booking_line_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_event_booking_line_booking FOREIGN KEY (event_booking_id, organization_id)
        REFERENCES event_booking (id, organization_id),
    CONSTRAINT fk_event_booking_line_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_event_booking_line_stock FOREIGN KEY (consumable_stock_id, organization_id)
        REFERENCES consumable_stock_balance (id, organization_id),
    CONSTRAINT ck_event_booking_line_variant CHECK (
        (line_type IN ('CONTAINER', 'ASSET') AND asset_id IS NOT NULL AND consumable_stock_id IS NULL AND quantity = 1)
        OR (line_type = 'CONSUMABLE' AND asset_id IS NULL AND consumable_stock_id IS NOT NULL AND quantity > 0)
    ),
    CONSTRAINT ck_event_booking_line_quantity_precision CHECK (scale(quantity) <= 3 AND abs(quantity) < 100000000000)
);
CREATE INDEX ix_event_booking_line_booking ON event_booking_line (organization_id, event_booking_id);

CREATE TABLE event_booking_reservation_revision (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    event_booking_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    action VARCHAR(20) NOT NULL,
    actor_user_id UUID REFERENCES app_user (id),
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_booking_reservation_revision_booking FOREIGN KEY (event_booking_id, organization_id)
        REFERENCES event_booking (id, organization_id),
    CONSTRAINT uk_booking_reservation_revision_id_organization UNIQUE (id, organization_id),
    CONSTRAINT uk_booking_revision_id_booking_organization UNIQUE (id,event_booking_id,organization_id),
    CONSTRAINT uk_booking_reservation_revision_number UNIQUE (event_booking_id, revision_number),
    CONSTRAINT ck_booking_reservation_revision_action CHECK (action IN ('RESERVED', 'RECALCULATED', 'CANCELLED')),
    CONSTRAINT ck_booking_reservation_revision_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE TABLE event_booking_reservation_claim (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    reservation_revision_id UUID NOT NULL,
    claim_type VARCHAR(30) NOT NULL,
    asset_id UUID,
    asset_model_id UUID,
    consumable_stock_id UUID,
    quantity NUMERIC NOT NULL DEFAULT 1,
    source_booking_line_id UUID,
    container_asset_id UUID,
    packing_requirement_id UUID,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_booking_claim_revision FOREIGN KEY (reservation_revision_id, organization_id)
        REFERENCES event_booking_reservation_revision (id, organization_id),
    CONSTRAINT fk_booking_claim_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_booking_claim_model FOREIGN KEY (asset_model_id, organization_id)
        REFERENCES asset_model (id, organization_id),
    CONSTRAINT fk_booking_claim_stock FOREIGN KEY (consumable_stock_id, organization_id)
        REFERENCES consumable_stock_balance (id, organization_id),
    CONSTRAINT fk_booking_claim_line FOREIGN KEY (source_booking_line_id, organization_id)
        REFERENCES event_booking_line (id, organization_id),
    CONSTRAINT fk_booking_claim_container FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_booking_claim_requirement FOREIGN KEY (packing_requirement_id, organization_id) REFERENCES packing_requirement (id, organization_id),
    CONSTRAINT ck_booking_claim_variant CHECK (
        (claim_type IN ('ASSET', 'FLEXIBLE_ASSET') AND asset_id IS NOT NULL AND asset_model_id IS NULL AND consumable_stock_id IS NULL AND quantity = 1)
        OR (claim_type = 'MODEL_CAPACITY' AND asset_id IS NULL AND asset_model_id IS NOT NULL AND consumable_stock_id IS NULL AND quantity > 0)
        OR (claim_type IN ('CONSUMABLE', 'CARRIED_CONSUMABLE') AND asset_id IS NULL AND asset_model_id IS NULL AND consumable_stock_id IS NOT NULL AND quantity > 0)
    ),
    CONSTRAINT ck_booking_claim_model_integer CHECK (claim_type != 'MODEL_CAPACITY' OR quantity = trunc(quantity)),
    CONSTRAINT ck_booking_claim_quantity_precision CHECK (scale(quantity) <= 3 AND abs(quantity) < 100000000000)
);
CREATE INDEX ix_booking_claim_asset ON event_booking_reservation_claim (organization_id, asset_id) WHERE asset_id IS NOT NULL;
CREATE INDEX ix_booking_claim_model ON event_booking_reservation_claim (organization_id, asset_model_id) WHERE asset_model_id IS NOT NULL;
CREATE INDEX ix_booking_claim_stock ON event_booking_reservation_claim (organization_id, consumable_stock_id) WHERE consumable_stock_id IS NOT NULL;

CREATE FUNCTION fn_reject_event_booking_reservation_history_mutation() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'booking reservation history is immutable' USING ERRCODE = '23514'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER tr_event_booking_reservation_revision_immutable BEFORE UPDATE OR DELETE ON event_booking_reservation_revision
    FOR EACH ROW EXECUTE FUNCTION fn_reject_event_booking_reservation_history_mutation();
CREATE TRIGGER tr_event_booking_reservation_claim_immutable BEFORE UPDATE OR DELETE ON event_booking_reservation_claim
    FOR EACH ROW EXECUTE FUNCTION fn_reject_event_booking_reservation_history_mutation();


ALTER TABLE event_booking ADD CONSTRAINT fk_booking_current_revision FOREIGN KEY (current_revision_id,id,organization_id) REFERENCES event_booking_reservation_revision (id,event_booking_id,organization_id);
ALTER TABLE event_booking ADD CONSTRAINT ck_booking_reservation_status CHECK (reservation_status IN ('NONE', 'CONFIRMED', 'ATTENTION_REQUIRED'));
CREATE TABLE event_booking_history (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    event_booking_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES app_user(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    snapshot JSONB NOT NULL,
    CONSTRAINT fk_booking_history_booking FOREIGN KEY (event_booking_id, organization_id) REFERENCES event_booking(id, organization_id),
    CONSTRAINT ck_booking_history_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);
CREATE INDEX ix_booking_history_booking ON event_booking_history (organization_id, event_booking_id, occurred_at);
CREATE TRIGGER tr_event_booking_history_immutable BEFORE UPDATE OR DELETE ON event_booking_history FOR EACH ROW EXECUTE FUNCTION fn_reject_event_booking_reservation_history_mutation();

ALTER TABLE stock_movement
    ADD CONSTRAINT fk_stock_movement_event_booking
    FOREIGN KEY (event_reference_id, organization_id)
    REFERENCES event_booking (id, organization_id);
