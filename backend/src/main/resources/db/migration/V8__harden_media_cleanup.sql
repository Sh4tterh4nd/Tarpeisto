ALTER TABLE media_object
    ADD COLUMN primary_image BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cleanup_completed_at TIMESTAMPTZ;

DROP INDEX uk_media_model_reference;
DROP INDEX uk_media_asset_reference;
DROP INDEX uk_media_container_layout_order;

CREATE UNIQUE INDEX uk_media_model_reference ON media_object (asset_model_id)
    WHERE purpose = 'MODEL_REFERENCE' AND archived_at IS NULL;
CREATE UNIQUE INDEX uk_media_asset_reference ON media_object (asset_id)
    WHERE purpose = 'ASSET_REFERENCE' AND archived_at IS NULL;
CREATE UNIQUE INDEX uk_media_container_layout_order ON media_object (asset_id, display_order)
    WHERE purpose = 'CONTAINER_LAYOUT' AND archived_at IS NULL;
CREATE UNIQUE INDEX uk_media_container_layout_primary ON media_object (asset_id)
    WHERE purpose = 'CONTAINER_LAYOUT' AND primary_image AND archived_at IS NULL;
