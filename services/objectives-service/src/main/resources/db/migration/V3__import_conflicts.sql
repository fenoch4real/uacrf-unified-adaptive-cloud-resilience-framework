-- Objective Import Preview & Upstream Conflict Resolution (AB.3)
CREATE TABLE objectives.import_conflict (
    conflict_id         UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    environment_id      UUID NOT NULL,
    service_canonical_id TEXT NOT NULL,
    sli_name            TEXT NOT NULL,
    local_slo_target    NUMERIC NOT NULL,
    incoming_slo_target NUMERIC NOT NULL,
    resolution_strategy TEXT NOT NULL DEFAULT 'UNRESOLVED',
    resolved_by         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE objectives.import_conflict ENABLE ROW LEVEL SECURITY;
ALTER TABLE objectives.import_conflict FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON objectives.import_conflict;
CREATE POLICY tenant_isolation_policy ON objectives.import_conflict
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
