CREATE TABLE uacrf_assessment.incident_idempotency (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 actor_id text NOT NULL,
 idempotency_key text NOT NULL,
 payload_digest char(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
 incident_id text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, actor_id, idempotency_key),
 FOREIGN KEY (tenant_id, environment_id, incident_id)
   REFERENCES uacrf_assessment.incident (tenant_id, environment_id, id)
);
