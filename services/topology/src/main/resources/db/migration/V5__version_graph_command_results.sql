ALTER TABLE uacrf_topology.graph_command
  DROP CONSTRAINT graph_command_status_check;

ALTER TABLE uacrf_topology.graph_command
  DROP CONSTRAINT graph_command_check;

UPDATE uacrf_topology.graph_command
SET status = 'ACCEPTED', terminal_outcome = NULL
WHERE status = 'PENDING';

UPDATE uacrf_topology.graph_command
SET status = 'SUCCEEDED', terminal_outcome = 'SUCCEEDED'
WHERE status IN ('INCOMPLETE', 'INDETERMINATE');

ALTER TABLE uacrf_topology.graph_command
  ADD CONSTRAINT graph_command_status_check
  CHECK (status IN (
    'ACCEPTED', 'RUNNING', 'WAITING_FOR_DEPENDENCY', 'RETRY_SCHEDULED',
    'SUCCEEDED', 'REJECTED', 'FAILED', 'CANCELLED', 'EXPIRED', 'OUTCOME_UNKNOWN'
  ));

ALTER TABLE uacrf_topology.graph_command
  ADD CONSTRAINT graph_command_lifecycle_check
  CHECK (
    (status IN ('ACCEPTED', 'RUNNING', 'WAITING_FOR_DEPENDENCY', 'RETRY_SCHEDULED', 'OUTCOME_UNKNOWN')
      AND terminal_outcome IS NULL)
    OR
    (status IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'CANCELLED', 'EXPIRED')
      AND terminal_outcome = status)
  );

ALTER TABLE uacrf_topology.graph_command_outbox
  ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN last_error text;

CREATE TABLE uacrf_topology.graph_domain_head (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('SUBGRAPH_SELECTION', 'DIFFERENCE_CLASSIFICATION')),
  target_key char(64) NOT NULL CHECK (target_key ~ '^[a-f0-9]{64}$'),
  current_revision bigint NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
  pending_command_id uuid REFERENCES uacrf_topology.graph_command(command_id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, environment_id, operation, target_key)
);

CREATE TABLE uacrf_topology.graph_domain_revision (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('SUBGRAPH_SELECTION', 'DIFFERENCE_CLASSIFICATION')),
  target_key char(64) NOT NULL CHECK (target_key ~ '^[a-f0-9]{64}$'),
  revision bigint NOT NULL CHECK (revision > 0),
  expected_revision bigint NOT NULL CHECK (expected_revision >= 0),
  command_id uuid NOT NULL UNIQUE REFERENCES uacrf_topology.graph_command(command_id),
  service_id text NOT NULL,
  graph_revision_id text NOT NULL,
  source_binding jsonb NOT NULL,
  selection_digest char(64) CHECK (selection_digest IS NULL OR selection_digest ~ '^[a-f0-9]{64}$'),
  selection_snapshot jsonb,
  comparison_snapshot jsonb,
  result jsonb,
  status text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  PRIMARY KEY (tenant_id, environment_id, operation, target_key, revision),
  CHECK ((status = 'PENDING' AND completed_at IS NULL)
      OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL))
);

CREATE INDEX graph_selection_digest_lookup
  ON uacrf_topology.graph_domain_revision
    (tenant_id, environment_id, service_id, graph_revision_id, selection_digest, revision DESC)
  WHERE operation = 'SUBGRAPH_SELECTION' AND status = 'SUCCEEDED'
    AND selection_digest IS NOT NULL;
