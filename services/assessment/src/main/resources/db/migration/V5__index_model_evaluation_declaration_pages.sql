CREATE INDEX model_evaluation_declaration_page_idx
 ON uacrf_assessment.model_evaluation_declaration (tenant_id, environment_id, model_version_id, id);
