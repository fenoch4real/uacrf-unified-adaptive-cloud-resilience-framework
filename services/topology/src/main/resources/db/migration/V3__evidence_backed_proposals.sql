ALTER TABLE uacrf_topology.graph_revisions ADD COLUMN digest_version integer NOT NULL DEFAULT 1;
CREATE TABLE uacrf_topology.proposal_evidence (
  tenant_id text NOT NULL, environment_id text NOT NULL, proposal_id text NOT NULL,
  source_version text NOT NULL, target_version text NOT NULL,
  evidence_id text NOT NULL, evidence_digest char(64) NOT NULL, evidence_revision bigint NOT NULL,
  source_receipt jsonb NOT NULL, target_receipt jsonb NOT NULL, summary jsonb NOT NULL,
  invalidated boolean NOT NULL DEFAULT false,
  PRIMARY KEY (tenant_id, environment_id, proposal_id),
  FOREIGN KEY (tenant_id, environment_id, proposal_id) REFERENCES uacrf_topology.dependency_proposals
);
