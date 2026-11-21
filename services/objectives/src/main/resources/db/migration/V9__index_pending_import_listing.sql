CREATE INDEX objective_import_preview_listing_idx
    ON uacrf_objectives.objective_import_preview_command
       (tenant_id, environment_id, command_id);
