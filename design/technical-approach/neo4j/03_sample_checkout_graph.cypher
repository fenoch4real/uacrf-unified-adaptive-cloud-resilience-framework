// Earlier explanatory fragment. For current implementation use database/ in the complete release.
// Do not apply alongside the current baseline migrations/projector. Historical source is retained in prior archives.
// SUPERSEDED for new implementations by database/README.md (Version 1.0 database revision 1).
// Retained reference fragment; do not combine with the new empty-database baseline.
// Synthetic checkout fixture; no production evidence or authorization.
// Qualifying these fixture values is simulated and never sufficient for a real action.
MERGE (t:Tenant {tenant_id:'tenant-demo'}) SET t.canonical_id='tenant-demo', t.name='Retail Demo';
MERGE (e:Environment {tenant_id:'tenant-demo', environment_id:'prod-us'}) SET e.canonical_id='prod-us', e.class='PRODUCTION';
MERGE (svc:Service {tenant_id:'tenant-demo', environment_id:'prod-us', service_id:'checkout'}) SET svc.canonical_id='svc-checkout', svc.name='Checkout', svc.criticality='P0';
MERGE (dep:Deployment {tenant_id:'tenant-demo', deployment_id:'checkout-v43'}) SET dep.canonical_id='dep-checkout-v43', dep.version='v43', dep.status='DEGRADED';
MERGE (step:JourneyStep {tenant_id:'tenant-demo', step_id:'authorize-payment'}) SET step.canonical_id='step-authorize-payment', step.sequence=3, step.mandatory=true;
MERGE (journey:CustomerJourney {tenant_id:'tenant-demo', journey_id:'place-order'}) SET journey.canonical_id='journey-place-order', journey.name='Place Order';
MERGE (segment:CustomerSegment {tenant_id:'tenant-demo', segment_id:'us-retail'}) SET segment.canonical_id='segment-us-retail', segment.definition='US retail customers';
MERGE (impact:Impact {tenant_id:'tenant-demo', impact_id:'impact-orders-4200'}) SET impact.canonical_id='impact-orders-4200', impact.impact_type='INCOMPLETE_ORDERS', impact.magnitude=4200, impact.unit='orders', impact.status='ESTIMATED';
MERGE (outcome:Outcome {tenant_id:'tenant-demo', outcome_id:'revenue-at-risk'}) SET outcome.canonical_id='outcome-revenue-risk', outcome.outcome_type='REVENUE_AT_RISK';
MERGE (dep)-[:IMPLEMENTS {relationship_id:'impl-1', tenant_id:'tenant-demo'}]->(svc)
MERGE (svc)-[:SUPPORTS_STEP {relationship_id:'support-1', tenant_id:'tenant-demo', criticality:'MANDATORY'}]->(step)
MERGE (step)-[:PART_OF_JOURNEY {relationship_id:'journey-1', tenant_id:'tenant-demo', sequence:3, mandatory:true}]->(journey)
MERGE (journey)-[:SERVES_SEGMENT {relationship_id:'segment-1', tenant_id:'tenant-demo'}]->(segment)
MERGE (segment)-[:AFFECTS {relationship_id:'impact-1', tenant_id:'tenant-demo', scenario:'checkout-v43-error', confidence:0.91}]->(impact)
MERGE (impact)-[:CONTRIBUTES_TO {relationship_id:'outcome-1', tenant_id:'tenant-demo', contribution_status:'ESTIMATED', confidence:0.78}]->(outcome);

// Scope and provenance of fixture nodes/edges; not a source verification process.
MATCH (n {tenant_id:"tenant-demo"})
SET n.environment_id="prod-us", n.qualification_state="QUALIFIED",
    n.confidence=1.0, n.source_kind="SYNTHETIC_FIXTURE",
    n.last_observed_at=datetime("2026-10-24T00:00:00Z"), n.expires_at=datetime("2026-10-25T00:00:00Z");
MATCH ()-[r {tenant_id:"tenant-demo"}]->()
SET r.environment_id="prod-us", r.qualification_state="QUALIFIED",
    r.valid_from=datetime("2026-10-24T00:00:00Z"), r.expires_at=datetime("2026-10-25T00:00:00Z");
