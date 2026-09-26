CREATE TABLE media_object (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    asset_model_id UUID,
    asset_id UUID,
    purpose VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    thumbnail_object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    caption VARCHAR(500),
    display_order INT NOT NULL DEFAULT 0,
    uploader_user_id UUID NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_media_object_asset_model FOREIGN KEY (asset_model_id, organization_id)
        REFERENCES asset_model (id, organization_id),
    CONSTRAINT fk_media_object_asset FOREIGN KEY (asset_id, organization_id)
        REFERENCES physical_asset (id, organization_id),
    CONSTRAINT uk_media_object_key UNIQUE (object_key),
    CONSTRAINT uk_media_thumbnail_key UNIQUE (thumbnail_object_key),
    CONSTRAINT ck_media_object_size_positive CHECK (byte_size > 0),
    CONSTRAINT ck_media_object_owner_and_purpose CHECK (
        (purpose = 'MODEL_REFERENCE' AND asset_model_id IS NOT NULL AND asset_id IS NULL
            AND caption IS NULL AND display_order = 0)
        OR (purpose = 'ASSET_REFERENCE' AND asset_id IS NOT NULL AND asset_model_id IS NULL
            AND caption IS NULL AND display_order = 0)
        OR (purpose = 'CONTAINER_LAYOUT' AND asset_id IS NOT NULL AND asset_model_id IS NULL)
    )
);

CREATE UNIQUE INDEX uk_media_model_reference ON media_object (asset_model_id)
    WHERE purpose = 'MODEL_REFERENCE';
CREATE UNIQUE INDEX uk_media_asset_reference ON media_object (asset_id)
    WHERE purpose = 'ASSET_REFERENCE';
CREATE UNIQUE INDEX uk_media_container_layout_order ON media_object (asset_id, display_order)
    WHERE purpose = 'CONTAINER_LAYOUT';
CREATE INDEX ix_media_object_organization ON media_object (organization_id);
CREATE INDEX ix_media_object_asset_layout ON media_object (asset_id, display_order)
    WHERE purpose = 'CONTAINER_LAYOUT';
