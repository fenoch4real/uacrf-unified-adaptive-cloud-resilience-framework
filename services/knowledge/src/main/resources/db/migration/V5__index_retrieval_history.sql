CREATE INDEX retrieval_history_cursor_idx
    ON uacrf_knowledge.retrieval (tenant_id, environment_id, retrieval_id COLLATE "C");
