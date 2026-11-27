CREATE SCHEMA IF NOT EXISTS foundation;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE foundation.tenant (
    tenant_id               uuid PRIMARY KEY,
    slug                    text NOT NULL UNIQUE,
    name                    text NOT NULL,
    status                  text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    default_retention_class text NOT NULL DEFAULT 'standard-7y',
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE foundation.environment (
    environment_id  uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES foundation.tenant(tenant_id),
    slug            text NOT NULL,
    name            text NOT NULL,
    env_class       text NOT NULL CHECK (env_class IN ('DEV', 'STAGING', 'PREPROD', 'PRODUCTION', 'REGULATED')),
    production      boolean NOT NULL DEFAULT false,
    authority_tier  text NOT NULL DEFAULT 'OBSERVE',
    status          text NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'RETIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

CREATE TABLE foundation.principal (
    principal_id      uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES foundation.tenant(tenant_id),
    environment_id    uuid NOT NULL REFERENCES foundation.environment(environment_id),
    principal_type    text NOT NULL CHECK (principal_type IN ('HUMAN', 'SYSTEM', 'AGENT')),
    issuer            text NOT NULL,
    external_subject  text NOT NULL,
    display_name      text NOT NULL,
    email             text,
    status            text NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    version           bigint NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, issuer, external_subject)
);

CREATE TABLE foundation.grant_decision (
    grant_decision_id     uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES foundation.tenant(tenant_id),
    environment_id        uuid NOT NULL REFERENCES foundation.environment(environment_id),
    target_principal_id   uuid NOT NULL REFERENCES foundation.principal(principal_id),
    capability            text NOT NULL,
    service_scope         text NOT NULL,
    approver_principal_id uuid NOT NULL REFERENCES foundation.principal(principal_id),
    decision              text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    rationale             text NOT NULL,
    expires_at            timestamptz NOT NULL,
    consumed_at           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CHECK (approver_principal_id <> target_principal_id)
);

CREATE TABLE foundation.membership (
    membership_id           uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES foundation.tenant(tenant_id),
    environment_id          uuid NOT NULL REFERENCES foundation.environment(environment_id),
    principal_id            uuid NOT NULL REFERENCES foundation.principal(principal_id),
    capability              text NOT NULL,
    service_scope           text NOT NULL,
    grant_decision_id       uuid NOT NULL UNIQUE REFERENCES foundation.grant_decision(grant_decision_id),
    granted_by_principal_id uuid NOT NULL REFERENCES foundation.principal(principal_id),
    expires_at              timestamptz NOT NULL,
    revoked_at              timestamptz,
    version                 bigint NOT NULL DEFAULT 1,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CHECK (granted_by_principal_id <> principal_id)
);

CREATE INDEX membership_active_idx
    ON foundation.membership (tenant_id, environment_id, principal_id, capability)
    WHERE revoked_at IS NULL;

CREATE TABLE foundation.idempotency_record (
    tenant_id       uuid NOT NULL,
    environment_id  uuid NOT NULL,
    actor_id        uuid NOT NULL,
    operation       text NOT NULL,
    idempotency_key text NOT NULL,
    request_digest  text NOT NULL,
    response_status integer NOT NULL,
    response_body   jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, actor_id, operation, idempotency_key)
);

CREATE TABLE foundation.audit_event (
    audit_event_id uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES foundation.tenant(tenant_id),
    environment_id uuid NOT NULL REFERENCES foundation.environment(environment_id),
    actor_id       uuid NOT NULL,
    action         text NOT NULL,
    entity_type    text NOT NULL,
    entity_id      uuid NOT NULL,
    reason         text,
    before_digest  text,
    after_digest   text,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integration.outbox_event (
    outbox_event_id   uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL,
    aggregate_type    text NOT NULL,
    aggregate_id      uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type        text NOT NULL,
    payload           jsonb NOT NULL,
    payload_digest    text NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT now(),
    publish_status    text NOT NULL DEFAULT 'pending',
    attempt_count     integer NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)
);

-- Bootstrap nonproduction tenant, environment, and separation-of-duties human roles for local dev
INSERT INTO foundation.tenant (tenant_id, slug, name, status)
VALUES ('11111111-1111-7111-8111-111111111111', 'default-org', 'Default Organization', 'ACTIVE');

INSERT INTO foundation.environment (environment_id, tenant_id, slug, name, env_class, production, authority_tier, status)
VALUES ('22222222-2222-7222-8222-222222222222', '11111111-1111-7111-8111-111111111111', 'nonproduction', 'Nonproduction Lab', 'STAGING', false, 'RECOMMEND', 'ACTIVE'),
       ('22222222-2222-7222-8222-222222222223', '11111111-1111-7111-8111-111111111111', 'production', 'Production Boundary', 'PRODUCTION', true, 'APPROVAL_GATED', 'ACTIVE');

INSERT INTO foundation.principal (principal_id, tenant_id, environment_id, principal_type, issuer, external_subject, display_name, email, status)
VALUES
  ('33333333-3333-7333-8333-333333333301', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'HUMAN', 'http://localhost:8180/realms/uacrf', 'admin-bootstrap', 'Platform Bootstrap Admin', 'admin@uacrf.local', 'ENABLED'),
  ('33333333-3333-7333-8333-333333333302', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'HUMAN', 'http://localhost:8180/realms/uacrf', 'approver-bootstrap', 'Independent Grant Approver', 'approver@uacrf.local', 'ENABLED'),
  ('33333333-3333-7333-8333-333333333303', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'HUMAN', 'http://localhost:8180/realms/uacrf', 'maya.chen', 'Maya Chen', 'maya.chen@uacrf.local', 'ENABLED'),
  ('33333333-3333-7333-8333-333333333304', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'HUMAN', 'http://localhost:8180/realms/uacrf', 'daniel.reed', 'Daniel Reed', 'daniel.reed@uacrf.local', 'ENABLED');
