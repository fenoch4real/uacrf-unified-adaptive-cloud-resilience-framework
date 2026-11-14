// Earlier explanatory fragment. For current implementation use database/ in the complete release.
// Do not apply alongside the current baseline migrations/projector. Historical source is retained in prior archives.
// SUPERSEDED for new implementations by database/README.md (Version 1.0 database revision 1).
// Retained reference fragment; do not combine with the new empty-database baseline.
// UACRF 1.0 alignment revision 3: managed-transaction fragments.
// Validate canonical parameters first. A zero-row result is rejection.
WITH $qualification_state AS qualification_state
WHERE qualification_state='QUALIFIED' AND $aggregate_version >= 0
MERGE (s:Service {tenant_id:$tenant_id, environment_id:$environment_id, service_id:$service_id})
ON CREATE SET s.catalog_version=-1, s.created_at=datetime($observed_at)
SET s._projection_lock=true
REMOVE s._projection_lock
WITH s, CASE
 WHEN $aggregate_version < s.catalog_version THEN 'STALE'
 WHEN $aggregate_version = s.catalog_version AND s.payload_digest=$payload_digest THEN 'DUPLICATE'
 WHEN $aggregate_version = s.catalog_version THEN 'CONFLICT'
 ELSE 'APPLIED' END AS disposition
FOREACH (_ IN CASE WHEN disposition='APPLIED' THEN [1] ELSE [] END |
 SET s.canonical_id=$canonical_id, s.name=$name, s.domain=$domain,
     s.criticality=$criticality, s.lifecycle=$lifecycle,
     s.catalog_version=$aggregate_version, s.source_version=$aggregate_version,
     s.payload_digest=$payload_digest, s.last_observed_at=datetime($observed_at),
     s.expires_at=datetime($expires_at), s.qualification_state='QUALIFIED',
     s.schema_version=$schema_version, s.provenance=$provenance_json)
RETURN disposition, s.catalog_version AS applied_version;

// Separate dependency statement within the documented managed batch.
MATCH (a {tenant_id:$tenant_id, environment_id:$environment_id, canonical_id:$source_id})
MATCH (b {tenant_id:$tenant_id, environment_id:$environment_id, canonical_id:$target_id})
WHERE $qualification_state='QUALIFIED'
 AND a.qualification_state='QUALIFIED' AND b.qualification_state='QUALIFIED'
 AND $source_version >= 0
 AND $criticality IN ['MANDATORY','DEGRADABLE','OPTIONAL','UNKNOWN']
MERGE (a)-[r:DEPENDS_ON {tenant_id:$tenant_id, environment_id:$environment_id, relationship_id:$relationship_id}]->(b)
ON CREATE SET r.source_version=-1
SET r._projection_lock=true
REMOVE r._projection_lock
WITH r, CASE
 WHEN $source_version < r.source_version THEN 'STALE'
 WHEN $source_version = r.source_version AND r.payload_digest=$payload_digest THEN 'DUPLICATE'
 WHEN $source_version = r.source_version THEN 'CONFLICT'
 ELSE 'APPLIED' END AS disposition
FOREACH (_ IN CASE WHEN disposition='APPLIED' THEN [1] ELSE [] END |
 SET r.criticality=$criticality, r.synchrony=$synchrony,
     r.propagation_rule=$propagation_rule_json, r.confidence=$confidence,
     r.provenance=$provenance_json, r.observed_at=datetime($observed_at),
     r.expires_at=datetime($expires_at), r.valid_from=datetime($valid_from),
     r.valid_to=CASE WHEN $valid_to IS NULL THEN null ELSE datetime($valid_to) END,
     r.source_version=$source_version, r.payload_digest=$payload_digest,
     r.qualification_state='QUALIFIED')
RETURN disposition, r.source_version AS applied_version;
