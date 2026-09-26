-- Tenancy root (specification section 3). Every later organization-owned table references
-- organization.id via organization_id. Table and column names are singular snake_case per
-- docs/DEVELOPMENT_POLICIES.md section 5.2, which takes precedence over the plural table names
-- used in prose elsewhere in the docs.
CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_organization_name ON organization (lower(name));
