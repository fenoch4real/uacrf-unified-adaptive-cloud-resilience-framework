-- Async Agent Execution Runtime Ledgers & Cancellation Fencing (FR-AWL-004..009, FR-ORC-001..006)
CREATE TABLE advisory.orchestration_run (
    run_id            UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    environment_id    UUID NOT NULL,
    workload_id       VARCHAR(120) NOT NULL,
    idempotency_key   VARCHAR(120) NOT NULL,
    request_digest    VARCHAR(128) NOT NULL,
    status            VARCHAR(40) NOT NULL,
    max_steps         INT NOT NULL,
    steps_used        INT NOT NULL DEFAULT 0,
    max_tokens        INT NOT NULL,
    tokens_used       INT NOT NULL DEFAULT 0,
    max_cost_cents    INT NOT NULL,
    cost_used_cents   INT NOT NULL DEFAULT 0,
    checkpoint_sha256 VARCHAR(128),
    parent_run_id     UUID,
    created_by        VARCHAR(120) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, idempotency_key)
);

CREATE TABLE advisory.orchestration_event (
    event_id          UUID PRIMARY KEY,
    run_id            UUID NOT NULL REFERENCES advisory.orchestration_run(run_id),
    tenant_id         UUID NOT NULL,
    environment_id    UUID NOT NULL,
    event_kind        VARCHAR(60) NOT NULL,
    accepted          BOOLEAN NOT NULL,
    detail            TEXT NOT NULL,
    event_digest      VARCHAR(128) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['advisory.orchestration_run', 'advisory.orchestration_event']
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_policy ON %s', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_policy ON %s USING (NULLIF(current_setting(''app.tenant_id'', true), '''') IS NULL OR tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            tbl
        );
    END LOOP;
END $$;
