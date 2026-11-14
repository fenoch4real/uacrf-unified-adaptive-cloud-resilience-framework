CREATE SCHEMA IF NOT EXISTS uacrf_topology;

CREATE TABLE uacrf_topology.permitted_relationships (
  source_type text NOT NULL, relationship_type text NOT NULL, target_type text NOT NULL,
  PRIMARY KEY (source_type, relationship_type, target_type)
);
INSERT INTO uacrf_topology.permitted_relationships VALUES
  ('service','DEPENDS_ON','service'), ('service','DEPENDS_ON','agent_workload'),
  ('service','DEPENDS_ON','shared_resource'), ('agent_workload','DEPENDS_ON','service'),
  ('agent_workload','DEPENDS_ON','agent_workload'), ('agent_workload','DEPENDS_ON','shared_resource'),
  ('shared_resource','DEPENDS_ON','shared_resource'), ('shared_resource','DEPENDS_ON','cloud_resource'),
  ('service','USES_MODEL','model'), ('agent_workload','USES_MODEL','model'),
  ('service','USES_IDENTITY','identity'), ('agent_workload','USES_IDENTITY','identity'),
  ('service','RUNS_IN','region'), ('agent_workload','RUNS_IN','region'),
  ('cloud_resource','RUNS_IN','region'), ('shared_resource','RUNS_IN','region'),
  ('service','HOSTED_ON','cloud_resource'), ('agent_workload','HOSTED_ON','cloud_resource'),
  ('shared_resource','HOSTED_ON','cloud_resource'), ('model','HOSTED_ON','cloud_resource'),
  ('service','IN_FAILURE_DOMAIN','failure_domain'), ('agent_workload','IN_FAILURE_DOMAIN','failure_domain'),
  ('cloud_resource','IN_FAILURE_DOMAIN','failure_domain'), ('region','IN_FAILURE_DOMAIN','failure_domain'),
  ('shared_resource','IN_FAILURE_DOMAIN','failure_domain');

CREATE TABLE uacrf_topology.dependency_proposals (
  tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL,
  source_type text NOT NULL, source_id text NOT NULL, relationship_type text NOT NULL,
  target_type text NOT NULL, target_id text NOT NULL,
  provenance_kind text NOT NULL CHECK (provenance_kind IN ('OBSERVED','AUTHORED')),
  source_system text, source_revision text, verified_at timestamptz,
  status text NOT NULL CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED')),
  proposer text NOT NULL, reviewer text, revision bigint NOT NULL CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT now(), reviewed_at timestamptz,
  PRIMARY KEY (tenant_id, environment_id, id),
  FOREIGN KEY (source_type, relationship_type, target_type) REFERENCES uacrf_topology.permitted_relationships,
  CHECK ((provenance_kind = 'OBSERVED' AND source_system IS NOT NULL AND source_revision IS NOT NULL AND verified_at IS NOT NULL)
      OR (provenance_kind = 'AUTHORED' AND source_system IS NULL AND source_revision IS NULL AND verified_at IS NULL)),
  CHECK (reviewer IS NULL OR reviewer <> proposer)
);
CREATE TABLE uacrf_topology.graph_revisions (
  tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL, scope text NOT NULL,
  revision_number bigint NOT NULL CHECK (revision_number > 0), digest char(64) NOT NULL,
  accepted_by text NOT NULL, accepted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, environment_id, id), UNIQUE (tenant_id, environment_id, scope, revision_number)
);
CREATE TABLE uacrf_topology.graph_nodes (
  tenant_id text NOT NULL, environment_id text NOT NULL, graph_id text NOT NULL, entity_type text NOT NULL, entity_id text NOT NULL,
  PRIMARY KEY (tenant_id, environment_id, graph_id, entity_type, entity_id),
  FOREIGN KEY (tenant_id, environment_id, graph_id) REFERENCES uacrf_topology.graph_revisions
);
CREATE TABLE uacrf_topology.graph_edges (
  tenant_id text NOT NULL, environment_id text NOT NULL, graph_id text NOT NULL,
  source_type text NOT NULL, source_id text NOT NULL, relationship_type text NOT NULL, target_type text NOT NULL, target_id text NOT NULL,
  proposal_id text NOT NULL,
  PRIMARY KEY (tenant_id, environment_id, graph_id, source_type, source_id, relationship_type, target_type, target_id),
  FOREIGN KEY (tenant_id, environment_id, graph_id, source_type, source_id) REFERENCES uacrf_topology.graph_nodes,
  FOREIGN KEY (tenant_id, environment_id, graph_id, target_type, target_id) REFERENCES uacrf_topology.graph_nodes,
  FOREIGN KEY (tenant_id, environment_id, proposal_id) REFERENCES uacrf_topology.dependency_proposals
);
CREATE TABLE uacrf_topology.graph_edge_conflicts (
  tenant_id text NOT NULL, environment_id text NOT NULL, graph_id text NOT NULL,
  proposal_id text NOT NULL, conflicting_proposal_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, environment_id, graph_id, proposal_id, conflicting_proposal_id),
  FOREIGN KEY (tenant_id, environment_id, graph_id) REFERENCES uacrf_topology.graph_revisions,
  FOREIGN KEY (tenant_id, environment_id, proposal_id) REFERENCES uacrf_topology.dependency_proposals,
  FOREIGN KEY (tenant_id, environment_id, conflicting_proposal_id) REFERENCES uacrf_topology.dependency_proposals,
  CHECK (proposal_id COLLATE "C" < conflicting_proposal_id COLLATE "C")
);
CREATE TABLE uacrf_topology.outbox_event (
  event_id uuid PRIMARY KEY, tenant_id text NOT NULL, environment_id text NOT NULL,
  aggregate_type text NOT NULL, aggregate_id text NOT NULL, aggregate_sequence bigint NOT NULL,
  event_type text NOT NULL, schema_version int NOT NULL DEFAULT 1, payload jsonb NOT NULL,
  payload_digest char(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz
);
