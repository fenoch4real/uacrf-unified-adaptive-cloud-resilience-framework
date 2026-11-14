ALTER TABLE uacrf_knowledge.retrieval
    ADD COLUMN case_digest char(64);

ALTER TABLE uacrf_knowledge.retrieval
    DROP CONSTRAINT retrieval_case_binding_check;

ALTER TABLE uacrf_knowledge.retrieval
    ADD CONSTRAINT retrieval_case_binding_check
        CHECK (case_binding IN ('NOT_VERIFIED', 'VERIFIED')),
    ADD CONSTRAINT retrieval_case_binding_facts_check
        CHECK (
            (case_binding = 'NOT_VERIFIED' AND case_digest IS NULL AND workload_id IS NULL)
            OR (case_binding = 'VERIFIED' AND case_id IS NOT NULL AND case_digest ~ '^[0-9a-f]{64}$'
                AND workload_id IS NOT NULL AND btrim(workload_id) <> '')
        );
