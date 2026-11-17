CREATE INDEX incident_case_id_idx
 ON uacrf_assessment.incident (tenant_id, environment_id, source_case_id, id);
