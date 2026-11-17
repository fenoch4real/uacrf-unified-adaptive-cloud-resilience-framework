INSERT INTO uacrf_topology.permitted_relationships
  (source_type, relationship_type, target_type)
VALUES
  ('service', 'MEMBER_OF_FAILURE_DOMAIN', 'failure_domain'),
  ('agent_workload', 'MEMBER_OF_FAILURE_DOMAIN', 'failure_domain'),
  ('cloud_resource', 'MEMBER_OF_FAILURE_DOMAIN', 'failure_domain'),
  ('region', 'MEMBER_OF_FAILURE_DOMAIN', 'failure_domain'),
  ('shared_resource', 'MEMBER_OF_FAILURE_DOMAIN', 'failure_domain')
ON CONFLICT DO NOTHING;
