CREATE TABLE uacrf_topology.graph_command (
  command_id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  actor_id text NOT NULL,
  service_id text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('SUBGRAPH_SELECTION','DIFFERENCE_CLASSIFICATION')),
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
  request_digest char(64) NOT NULL,
  request_body jsonb NOT NULL,
  source_binding jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING','INCOMPLETE','INDETERMINATE')),
  terminal_outcome text,
  result jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, environment_id, actor_id, operation, idempotency_key),
  CHECK ((status = 'PENDING' AND terminal_outcome IS NULL AND result IS NULL)
      OR (status <> 'PENDING' AND terminal_outcome = status AND result IS NOT NULL))
);

CREATE TABLE uacrf_topology.graph_command_outbox (
  event_id uuid PRIMARY KEY,
  command_id uuid NOT NULL UNIQUE REFERENCES uacrf_topology.graph_command,
  event_type text NOT NULL CHECK (event_type = 'uacrf.s06.graph.command.accepted.v1'),
  payload jsonb NOT NULL,
  payload_digest char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  claimed_at timestamptz,
  lease_owner text,
  lease_expires_at timestamptz,
  processed_at timestamptz,
  CHECK ((lease_owner IS NULL AND lease_expires_at IS NULL)
      OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX graph_command_outbox_ready
  ON uacrf_topology.graph_command_outbox (created_at, event_id)
  WHERE processed_at IS NULL;
