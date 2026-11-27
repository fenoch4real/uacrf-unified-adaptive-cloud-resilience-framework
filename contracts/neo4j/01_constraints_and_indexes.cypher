// UACRF Canonical Neo4j Constraints & Indexes (v1.0 alignment rev 7)
// Note: Community Edition supports UNIQUENESS constraints and B-tree/FULLTEXT indexes;
// Enterprise deployments may convert key pairs to NODE KEY constraints.

CREATE CONSTRAINT tenant_canonical IF NOT EXISTS
FOR (n:Tenant) REQUIRE (n.tenant_id) IS UNIQUE;

CREATE CONSTRAINT environment_canonical IF NOT EXISTS
FOR (n:Environment) REQUIRE (n.tenant_id, n.environment_id) IS UNIQUE;

CREATE CONSTRAINT service_canonical IF NOT EXISTS
FOR (n:Service) REQUIRE (n.tenant_id, n.environment_id, n.service_id) IS UNIQUE;

CREATE CONSTRAINT cloud_resource_canonical IF NOT EXISTS
FOR (n:CloudResource) REQUIRE (n.tenant_id, n.provider, n.account_id, n.native_id) IS UNIQUE;

CREATE CONSTRAINT model_endpoint_canonical IF NOT EXISTS
FOR (n:ModelEndpoint) REQUIRE (n.tenant_id, n.model_endpoint_id) IS UNIQUE;

CREATE CONSTRAINT customer_journey_canonical IF NOT EXISTS
FOR (n:CustomerJourney) REQUIRE (n.tenant_id, n.journey_id) IS UNIQUE;

CREATE CONSTRAINT graph_revision_canonical IF NOT EXISTS
FOR (n:GraphRevision) REQUIRE (n.tenant_id, n.environment_id, n.graph_revision) IS UNIQUE;

CREATE CONSTRAINT decision_envelope_canonical IF NOT EXISTS
FOR (n:DecisionEnvelope) REQUIRE (n.tenant_id, n.decision_id) IS UNIQUE;

CREATE CONSTRAINT evidence_event_canonical IF NOT EXISTS
FOR (n:EvidenceEvent) REQUIRE (n.tenant_id, n.event_id) IS UNIQUE;

CREATE INDEX service_criticality_idx IF NOT EXISTS
FOR (n:Service) ON (n.tenant_id, n.environment_id, n.criticality);

CREATE INDEX resource_region_idx IF NOT EXISTS
FOR (n:CloudResource) ON (n.tenant_id, n.provider, n.region);

CREATE INDEX canonical_id_service_idx IF NOT EXISTS
FOR (n:Service) ON (n.canonical_id);

CREATE FULLTEXT INDEX topology_search IF NOT EXISTS
FOR (n:Service|ApplicationComponent|API|CloudResource|AIModel|ExternalProvider|CustomerJourney)
ON EACH [n.name, n.canonical_id, n.native_id];
