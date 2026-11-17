INSERT INTO uacrf_topology.permitted_relationships
  (source_type, relationship_type, target_type)
VALUES
  ('service', 'READS_FROM', 'shared_resource'),
  ('agent_workload', 'READS_FROM', 'shared_resource')
ON CONFLICT DO NOTHING;
