CREATE TABLE uacrf_catalog.owner_transfer_audit (
    audit_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    object_kind text NOT NULL CHECK (object_kind IN ('JOURNEY', 'SERVICE')),
    object_id text NOT NULL,
    previous_owner_id text NOT NULL,
    owner_id text NOT NULL,
    previous_revision bigint NOT NULL CHECK (previous_revision > 0),
    revision bigint NOT NULL CHECK (revision = previous_revision + 1),
    actor_id text NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, object_kind, object_id, revision)
);

CREATE FUNCTION uacrf_catalog.protect_owner_transfer_audit()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Owner transfer audit records are immutable and retained';
END;
$$;

CREATE TRIGGER owner_transfer_audit_immutable
    BEFORE UPDATE OR DELETE ON uacrf_catalog.owner_transfer_audit
    FOR EACH ROW EXECUTE FUNCTION uacrf_catalog.protect_owner_transfer_audit();
