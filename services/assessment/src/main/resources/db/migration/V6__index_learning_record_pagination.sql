CREATE INDEX learning_record_incident_id_idx
 ON uacrf_assessment.learning_record (tenant_id, environment_id, incident_id, id);
