-- Reusable PostgreSQL schema template for UACRF transactional outbox and lease fencing
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE IF NOT EXISTS integration.outbox_event (
    outbox_event_id    uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL,
    aggregate_type     text NOT NULL,
    aggregate_id       uuid NOT NULL,
    aggregate_version  bigint NOT NULL,
    event_type         text NOT NULL,
    payload            jsonb NOT NULL,
    payload_digest     text NOT NULL,
    occurred_at        timestamptz NOT NULL DEFAULT now(),
    publish_status     text NOT NULL DEFAULT 'pending'
        CHECK (publish_status IN ('pending', 'retry', 'published', 'quarantined')),
    attempt_count      integer NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL DEFAULT now(),
    lease_token        text,
    lease_expires_at   timestamptz,
    published_at       timestamptz,
    last_error         text,
    UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx
    ON integration.outbox_event (next_attempt_at, occurred_at)
    WHERE publish_status IN ('pending', 'retry');
