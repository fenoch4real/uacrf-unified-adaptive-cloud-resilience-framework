CREATE TABLE uacrf_objectives.objective_decision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    objective_id text NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 2),
    content_digest char(64) NOT NULL CHECK (content_digest ~ '^[a-f0-9]{64}$'),
    decision text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    decided_by text NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, objective_id, revision),
    FOREIGN KEY (tenant_id, environment_id, objective_id)
        REFERENCES uacrf_objectives.objective (tenant_id, environment_id, id)
);

CREATE FUNCTION uacrf_objectives.reject_objective_decision_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'objective decisions are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER objective_decision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.objective_decision
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_objective_decision_mutation();
