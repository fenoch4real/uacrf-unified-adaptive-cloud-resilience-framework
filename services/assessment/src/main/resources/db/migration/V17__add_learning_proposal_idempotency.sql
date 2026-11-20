CREATE TABLE uacrf_assessment.learning_proposal_idempotency (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 actor_id text NOT NULL,
 idempotency_key text NOT NULL,
 payload_digest char(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
 learning_record_id text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, actor_id, idempotency_key),
 FOREIGN KEY (tenant_id, environment_id, learning_record_id)
   REFERENCES uacrf_assessment.learning_record (tenant_id, environment_id, id)
);
