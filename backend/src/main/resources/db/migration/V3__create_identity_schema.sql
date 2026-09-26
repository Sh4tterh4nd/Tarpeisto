-- Phase 1 identity core (specification sections 3-4, ADR-0003). Table and column names are
-- singular snake_case per docs/DEVELOPMENT_POLICIES.md section 5.2.
--
-- app_user is named "app_user" rather than "user": "user" is a reserved word in the SQL
-- standard and in PostgreSQL, which would force every statement referencing it to use quoted
-- identifiers. app_user is a permanent-account principal; it is intentionally NOT itself
-- organization-owned (no organization_id column) because organization membership - and
-- therefore role and active-organization context - is the explicit, separate
-- organization_membership table per the implementation plan's Phase 1 data model and
-- specification section 3.2 ("A future commercial installation may add organization
-- memberships, switching, limits, and billing without rewriting the inventory schema.").
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255) NOT NULL,
    -- Nullable: a future OIDC-only permanent user (Phase 1 follow-up) may have no local
    -- credential at all, authenticating only through a linked external_identity row. Local
    -- authentication treats a null password_hash as "local login unavailable for this user".
    password_hash VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Username is the local-login identifier and is unique case-insensitively across the whole
-- installation (not per-organization): login happens before an organization is known.
CREATE UNIQUE INDEX uk_app_user_username ON app_user (lower(username));

-- Links a permanent app_user to one organization with one role (specification section 4.1).
-- Even though the initial product shows exactly one organization, membership is explicit per
-- the implementation plan so a future multi-organization installation does not require a
-- schema rewrite.
CREATE TABLE organization_membership (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    user_id UUID NOT NULL REFERENCES app_user (id),
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_organization_membership_org_user UNIQUE (organization_id, user_id),
    CONSTRAINT ck_organization_membership_role
        CHECK (role IN ('OWNER', 'DEPUTY', 'OPERATOR_AUDITOR', 'VIEWER'))
);

CREATE INDEX ix_organization_membership_organization ON organization_membership (organization_id);
CREATE INDEX ix_organization_membership_user ON organization_membership (user_id);

-- Schema only for now (per the Phase 1 task scope, OIDC behavior is implemented separately).
-- The durable external identity key is the immutable pair (issuer, subject); email is a
-- diagnostic/profile attribute only, never the login key (ADR-0003, specification section 4.3
-- and 28).
CREATE TABLE external_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user (id),
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    last_email VARCHAR(255),
    last_display_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    unlinked_at TIMESTAMPTZ,
    CONSTRAINT uk_external_identity_issuer_subject UNIQUE (issuer, subject)
);

CREATE INDEX ix_external_identity_user ON external_identity (user_id);

-- Initial activity/history infrastructure (specification section 25). One append-only row per
-- recorded event; rows are never updated or deleted by application code. organization_id is
-- always the organization the acting principal was scoped to at the time of the action.
-- actor_user_id is nullable to allow a future system-initiated entry, but every entry created
-- by an authenticated request in Phase 1 always sets it.
CREATE TABLE activity_log (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id),
    actor_user_id UUID REFERENCES app_user (id),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID,
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_activity_log_organization ON activity_log (organization_id, occurred_at DESC);
CREATE INDEX ix_activity_log_target ON activity_log (target_type, target_id);
