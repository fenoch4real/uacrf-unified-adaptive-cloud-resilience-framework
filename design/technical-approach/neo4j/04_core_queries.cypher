// Earlier explanatory fragment. For current implementation use database/ in the complete release.
// Do not apply alongside the current baseline migrations/projector. Historical source is retained in prior archives.
// SUPERSEDED for new implementations by database/README.md (Version 1.0 database revision 1).
// Retained reference fragment; do not combine with the new empty-database baseline.
// UACRF 1.0 alignment revision 3: current-state candidate extraction ONLY.
// Not a historical impact result, authorization or path certificate.
// Caller must seal a revision-consistent authenticated snapshot, then run
// bounded_impact / expand_upstream / certify from core_contracts.py.
// Abort when graph revision or canonical snapshot digest changes during capture.
MATCH (affected {tenant_id:$tenant_id, environment_id:$environment_id, canonical_id:$origin_id})
MATCH p=(dependent)-[:DEPENDS_ON|CALLS|READS_FROM|WRITES_TO|USES_MODEL|USES_IDENTITY|USES_KEY|USES_DNS|RUNS_ON*1..8]->(affected)
WHERE length(p) <= $max_depth AND $max_depth >= 1 AND $max_depth <= 8
 AND all(n IN nodes(p) WHERE n.tenant_id=$tenant_id AND n.environment_id=$environment_id
     AND n.qualification_state='QUALIFIED' AND n.expires_at > datetime($as_of))
 AND all(r IN relationships(p) WHERE r.tenant_id=$tenant_id AND r.environment_id=$environment_id
     AND r.qualification_state='QUALIFIED' AND r.expires_at > datetime($as_of)
     AND r.confidence >= $minimum_confidence AND r.valid_from <= datetime($as_of)
     AND (r.valid_to IS NULL OR r.valid_to > datetime($as_of)))
RETURN [n IN nodes(p) | n.canonical_id] AS dependency_witness,
       [r IN relationships(p) | r.relationship_id] AS relationship_ids, length(p) AS depth;

// Domain witnesses for both bounded expansions. This query does not certify.
MATCH (n {tenant_id:$tenant_id, environment_id:$environment_id})
      -[m:MEMBER_OF_FAILURE_DOMAIN]->(d:FailureDomain)
WHERE n.canonical_id IN $expanded_node_ids
 AND d.tenant_id=$tenant_id AND d.environment_id=$environment_id
 AND m.tenant_id=$tenant_id AND m.environment_id=$environment_id
 AND n.qualification_state='QUALIFIED' AND d.qualification_state='QUALIFIED'
 AND m.qualification_state='QUALIFIED'
 AND n.expires_at > datetime($as_of) AND d.expires_at > datetime($as_of)
 AND m.expires_at > datetime($as_of)
RETURN n.canonical_id AS member_id, d.domain_type AS domain_type,
       d.failure_domain_id AS domain_id, m.relationship_id AS witness_relationship;
