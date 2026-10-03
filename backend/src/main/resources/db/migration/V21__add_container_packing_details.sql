ALTER TABLE physical_asset
    ADD COLUMN container_color varchar(7) NOT NULL DEFAULT '#FFFFFF',
    ADD COLUMN unit_description varchar(500),
    ADD CONSTRAINT physical_asset_container_color_check CHECK (container_color ~ '^#[0-9A-F]{6}$');
