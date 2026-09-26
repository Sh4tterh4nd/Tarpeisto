-- Phase 5: independently versioned container requirements and copy-only templates.
CREATE TABLE packing_template (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    name VARCHAR(160) NOT NULL,
    description TEXT,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_packing_template_id_organization UNIQUE (id, organization_id)
);
CREATE UNIQUE INDEX uk_packing_template_active_name ON packing_template (organization_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE packing_template_requirement (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    packing_template_id UUID NOT NULL,
    requirement_type VARCHAR(30) NOT NULL,
    asset_model_id UUID,
    specific_asset_id UUID,
    required_quantity NUMERIC NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_template_requirement_template FOREIGN KEY (packing_template_id, organization_id) REFERENCES packing_template (id, organization_id),
    CONSTRAINT fk_template_requirement_model FOREIGN KEY (asset_model_id, organization_id) REFERENCES asset_model (id, organization_id),
    CONSTRAINT fk_template_requirement_asset FOREIGN KEY (specific_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT uk_template_requirement_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_template_requirement_variant CHECK ((requirement_type = 'SPECIFIC_ASSET' AND specific_asset_id IS NOT NULL AND asset_model_id IS NULL AND required_quantity = 1) OR (requirement_type IN ('MODEL_QUANTITY','CONSUMABLE_QUANTITY') AND asset_model_id IS NOT NULL AND specific_asset_id IS NULL AND required_quantity > 0)),
    CONSTRAINT ck_template_requirement_quantity_precision CHECK (scale(required_quantity) <= 3 AND abs(required_quantity) < 100000000000),
    CONSTRAINT ck_template_requirement_model_quantity_integral CHECK (requirement_type <> 'MODEL_QUANTITY' OR (required_quantity = trunc(required_quantity) AND required_quantity <= 2147483647))
);
CREATE UNIQUE INDEX uk_active_template_requirement_mode_model ON packing_template_requirement (packing_template_id, requirement_type, asset_model_id) WHERE archived_at IS NULL AND requirement_type IN ('MODEL_QUANTITY','CONSUMABLE_QUANTITY');
CREATE UNIQUE INDEX uk_active_template_requirement_exact_asset ON packing_template_requirement (packing_template_id, specific_asset_id) WHERE archived_at IS NULL AND requirement_type = 'SPECIFIC_ASSET';

CREATE TABLE packing_requirement (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    container_asset_id UUID NOT NULL,
    requirement_type VARCHAR(30) NOT NULL,
    asset_model_id UUID,
    specific_asset_id UUID,
    required_quantity NUMERIC NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_packing_requirement_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_packing_requirement_container FOREIGN KEY (container_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT fk_packing_requirement_model FOREIGN KEY (asset_model_id, organization_id) REFERENCES asset_model (id, organization_id),
    CONSTRAINT fk_packing_requirement_asset FOREIGN KEY (specific_asset_id, organization_id) REFERENCES physical_asset (id, organization_id),
    CONSTRAINT ck_packing_requirement_variant CHECK ((requirement_type = 'SPECIFIC_ASSET' AND specific_asset_id IS NOT NULL AND asset_model_id IS NULL AND required_quantity = 1) OR (requirement_type IN ('MODEL_QUANTITY','CONSUMABLE_QUANTITY') AND asset_model_id IS NOT NULL AND specific_asset_id IS NULL AND required_quantity > 0)),
    CONSTRAINT ck_packing_requirement_not_self CHECK (specific_asset_id IS NULL OR specific_asset_id <> container_asset_id),
    CONSTRAINT ck_packing_requirement_quantity_precision CHECK (scale(required_quantity) <= 3 AND abs(required_quantity) < 100000000000),
    CONSTRAINT ck_packing_requirement_model_quantity_integral CHECK (requirement_type <> 'MODEL_QUANTITY' OR (required_quantity = trunc(required_quantity) AND required_quantity <= 2147483647))
);
CREATE INDEX ix_packing_requirement_container ON packing_requirement (organization_id, container_asset_id) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX uk_active_exact_requirement_asset ON packing_requirement (organization_id, specific_asset_id) WHERE archived_at IS NULL AND requirement_type = 'SPECIFIC_ASSET';
CREATE UNIQUE INDEX uk_active_packing_requirement_mode_model ON packing_requirement (container_asset_id, requirement_type, asset_model_id) WHERE archived_at IS NULL AND requirement_type IN ('MODEL_QUANTITY','CONSUMABLE_QUANTITY');

CREATE FUNCTION fn_validate_packing_requirement() RETURNS TRIGGER AS $$
DECLARE model_mode VARCHAR(20); container_capable BOOLEAN;
BEGIN
 PERFORM id FROM organization WHERE id = NEW.organization_id FOR UPDATE;
 SELECT am.can_contain_assets INTO container_capable FROM physical_asset pa JOIN asset_model am ON am.id=pa.asset_model_id WHERE pa.id=NEW.container_asset_id;
 IF container_capable IS NOT TRUE THEN RAISE EXCEPTION 'only a container-capable asset can own packing requirements' USING ERRCODE='23514'; END IF;
 IF NEW.requirement_type <> 'SPECIFIC_ASSET' THEN
   SELECT tracking_mode INTO model_mode FROM asset_model WHERE id=NEW.asset_model_id;
   IF (NEW.requirement_type='MODEL_QUANTITY' AND model_mode <> 'SERIALIZED_ASSET') OR (NEW.requirement_type='CONSUMABLE_QUANTITY' AND model_mode <> 'QUANTITY_STOCK') THEN RAISE EXCEPTION 'packing requirement type does not match model tracking mode' USING ERRCODE='23514'; END IF;
 ELSIF EXISTS (SELECT 1 FROM physical_asset pa JOIN asset_model am ON am.id=pa.asset_model_id WHERE pa.id=NEW.specific_asset_id AND am.tracking_mode <> 'SERIALIZED_ASSET') THEN RAISE EXCEPTION 'exact requirement must reference serialized asset' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_validate_packing_requirement BEFORE INSERT OR UPDATE ON packing_requirement FOR EACH ROW EXECUTE FUNCTION fn_validate_packing_requirement();

CREATE FUNCTION fn_validate_packing_template_requirement() RETURNS TRIGGER AS $$
DECLARE model_mode VARCHAR(20);
BEGIN
 PERFORM id FROM organization WHERE id = NEW.organization_id FOR UPDATE;
 IF NEW.requirement_type <> 'SPECIFIC_ASSET' THEN
   SELECT tracking_mode INTO model_mode FROM asset_model WHERE id=NEW.asset_model_id;
   IF (NEW.requirement_type='MODEL_QUANTITY' AND model_mode <> 'SERIALIZED_ASSET') OR (NEW.requirement_type='CONSUMABLE_QUANTITY' AND model_mode <> 'QUANTITY_STOCK') THEN RAISE EXCEPTION 'template requirement type does not match model tracking mode' USING ERRCODE='23514'; END IF;
 ELSIF EXISTS (SELECT 1 FROM physical_asset pa JOIN asset_model am ON am.id=pa.asset_model_id WHERE pa.id=NEW.specific_asset_id AND am.tracking_mode <> 'SERIALIZED_ASSET') THEN RAISE EXCEPTION 'template exact requirement must reference serialized asset' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_validate_packing_template_requirement BEFORE INSERT OR UPDATE ON packing_template_requirement FOR EACH ROW EXECUTE FUNCTION fn_validate_packing_template_requirement();

CREATE TABLE packing_requirement_history (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    packing_requirement_id UUID,
    packing_template_requirement_id UUID,
    action VARCHAR(30) NOT NULL,
    actor_user_id UUID REFERENCES app_user (id),
    occurred_at TIMESTAMPTZ NOT NULL,
    details TEXT NOT NULL,
    CONSTRAINT ck_packing_history_one_owner CHECK ((packing_requirement_id IS NOT NULL) <> (packing_template_requirement_id IS NOT NULL)),
    CONSTRAINT ck_packing_history_json CHECK (jsonb_typeof(details::jsonb) = 'object'),
    CONSTRAINT fk_packing_requirement_history_requirement FOREIGN KEY (packing_requirement_id, organization_id) REFERENCES packing_requirement (id, organization_id),
    CONSTRAINT fk_packing_history_template_requirement FOREIGN KEY (packing_template_requirement_id, organization_id) REFERENCES packing_template_requirement (id, organization_id)
);
CREATE INDEX ix_packing_history_requirement ON packing_requirement_history (organization_id, packing_requirement_id, occurred_at);
CREATE INDEX ix_packing_history_template_requirement ON packing_requirement_history (organization_id, packing_template_requirement_id, occurred_at);

-- Changes to tracking mode must preserve current and historical requirement semantics.
-- The shared organization lock serializes this trigger with application requirement mutations.
CREATE FUNCTION fn_reject_tracking_mode_with_packing() RETURNS TRIGGER AS $$
BEGIN
 IF NEW.tracking_mode IS DISTINCT FROM OLD.tracking_mode THEN
   PERFORM id FROM organization WHERE id = NEW.organization_id FOR UPDATE;
   IF EXISTS (SELECT 1 FROM packing_requirement WHERE organization_id = NEW.organization_id AND asset_model_id = NEW.id)
      OR EXISTS (SELECT 1 FROM packing_template_requirement WHERE organization_id = NEW.organization_id AND asset_model_id = NEW.id)
      OR EXISTS (SELECT 1 FROM packing_requirement_history WHERE organization_id = NEW.organization_id
                 AND (details::jsonb #>> '{before,assetModelId}' = NEW.id::text
                   OR details::jsonb #>> '{after,assetModelId}' = NEW.id::text)) THEN
     RAISE EXCEPTION 'Cannot change tracking mode while packing requirements or their history exist' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_asset_model_reject_tracking_mode_with_packing BEFORE UPDATE OF tracking_mode ON asset_model FOR EACH ROW EXECUTE FUNCTION fn_reject_tracking_mode_with_packing();
CREATE FUNCTION fn_reject_packing_requirement_history_mutation() RETURNS TRIGGER AS $$ BEGIN RAISE EXCEPTION 'packing requirement history is immutable' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER tr_packing_requirement_history_reject_update BEFORE UPDATE ON packing_requirement_history FOR EACH ROW EXECUTE FUNCTION fn_reject_packing_requirement_history_mutation();
CREATE TRIGGER tr_packing_requirement_history_reject_delete BEFORE DELETE ON packing_requirement_history FOR EACH ROW EXECUTE FUNCTION fn_reject_packing_requirement_history_mutation();
