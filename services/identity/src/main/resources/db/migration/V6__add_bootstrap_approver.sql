ALTER TABLE uacrf_identity.bootstrap_audit
    ADD COLUMN approver_principal_id text REFERENCES uacrf_identity.principal (id),
    ADD COLUMN approver_subject text,
    ADD COLUMN approver_capabilities text,
    ADD CONSTRAINT bootstrap_approver_complete CHECK (
        (approver_principal_id IS NULL AND approver_subject IS NULL AND approver_capabilities IS NULL)
        OR (approver_principal_id IS NOT NULL AND approver_subject IS NOT NULL
            AND approver_capabilities IS NOT NULL
            AND approver_principal_id <> principal_id AND approver_subject <> subject));
