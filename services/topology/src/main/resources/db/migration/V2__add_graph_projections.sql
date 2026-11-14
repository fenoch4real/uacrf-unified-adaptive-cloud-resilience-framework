CREATE TABLE uacrf_topology.graph_projections (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  graph_id text NOT NULL,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROJECTING','PROJECTED','FAILED','DRIFTED')),
  lease_owner text,
  lease_expires_at timestamptz,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  projected_node_count integer NOT NULL DEFAULT 0 CHECK (projected_node_count >= 0),
  projected_edge_count integer NOT NULL DEFAULT 0 CHECK (projected_edge_count >= 0),
  verified_digest char(64),
  last_error text,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, environment_id, graph_id),
  FOREIGN KEY (tenant_id, environment_id, graph_id)
    REFERENCES uacrf_topology.graph_revisions (tenant_id, environment_id, id),
  CHECK ((status = 'PROJECTING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
      OR (status <> 'PROJECTING' AND lease_owner IS NULL AND lease_expires_at IS NULL))
);
CREATE INDEX graph_projections_claim_idx
  ON uacrf_topology.graph_projections (status, next_attempt_at, created_at);

INSERT INTO uacrf_topology.graph_projections (tenant_id, environment_id, graph_id, status)
SELECT tenant_id, environment_id, id, 'PENDING'
FROM uacrf_topology.graph_revisions
ON CONFLICT (tenant_id, environment_id, graph_id) DO NOTHING;
