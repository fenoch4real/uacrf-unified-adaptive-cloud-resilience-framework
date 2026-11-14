CREATE SCHEMA IF NOT EXISTS uacrf_identity;

CREATE TABLE uacrf_identity.tenant (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_identity.environment (
    tenant_id text NOT NULL REFERENCES uacrf_identity.tenant (id),
    id text NOT NULL,
    display_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE uacrf_identity.identity_provider (
    id text PRIMARY KEY,
    issuer_uri text NOT NULL UNIQUE,
    display_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('TRUSTED', 'DISABLED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_identity.principal (
    id text PRIMARY KEY,
    issuer_uri text NOT NULL REFERENCES uacrf_identity.identity_provider (issuer_uri),
    subject text NOT NULL,
    display_name text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('HUMAN', 'SYSTEM', 'AGENT')),
    enabled boolean NOT NULL,
    token_valid_after timestamptz NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (issuer_uri, subject)
);

CREATE TABLE uacrf_identity.membership (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    capability text NOT NULL,
    service_scope text NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, environment_id)
        REFERENCES uacrf_identity.environment (tenant_id, id)
);

CREATE TABLE uacrf_identity.bootstrap_audit (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    provider_id text NOT NULL REFERENCES uacrf_identity.identity_provider (id),
    issuer_uri text NOT NULL,
    subject text NOT NULL,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    admin_expires_at timestamptz NOT NULL,
    granted_capabilities text NOT NULL,
    bootstrap_reason text NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_identity.bootstrap_guard (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    initialized boolean NOT NULL DEFAULT false
);

INSERT INTO uacrf_identity.bootstrap_guard (singleton, initialized) VALUES (true, false);

CREATE INDEX membership_scope_principal_idx
    ON uacrf_identity.membership (tenant_id, environment_id, principal_id, capability)
    WHERE revoked_at IS NULL;
