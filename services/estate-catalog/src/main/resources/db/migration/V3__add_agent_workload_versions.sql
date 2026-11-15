ALTER TABLE uacrf_catalog.agent_workload
    ADD COLUMN active_version_id text;

CREATE TABLE uacrf_catalog.agent_workload_version (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    workload_id text NOT NULL,
    id text NOT NULL,
    version_number bigint NOT NULL CHECK (version_number > 0),
    digest char(64) NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    object_ref text NOT NULL CHECK (length(object_ref) BETWEEN 1 AND 4096),
    manifest jsonb NOT NULL,
    created_by text NOT NULL,
    state text NOT NULL CHECK (state IN ('REGISTERED', 'ACTIVE', 'SUPERSEDED')),
    revision bigint NOT NULL CHECK (revision > 0),
    activated_by text,
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, workload_id, id),
    UNIQUE (tenant_id, environment_id, workload_id, version_number),
    FOREIGN KEY (tenant_id, environment_id, workload_id)
        REFERENCES uacrf_catalog.agent_workload (tenant_id, environment_id, id),
    CHECK (
        (state = 'REGISTERED' AND activated_by IS NULL AND activated_at IS NULL)
        OR
        (state IN ('ACTIVE', 'SUPERSEDED') AND activated_by IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX agent_workload_one_active_version_idx
    ON uacrf_catalog.agent_workload_version (tenant_id, environment_id, workload_id)
    WHERE state = 'ACTIVE';

ALTER TABLE uacrf_catalog.agent_workload
    ADD CONSTRAINT agent_workload_active_version_fk
    FOREIGN KEY (tenant_id, environment_id, id, active_version_id)
    REFERENCES uacrf_catalog.agent_workload_version (tenant_id, environment_id, workload_id, id);

CREATE FUNCTION uacrf_catalog.protect_agent_workload_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Agent workload versions are immutable and retained';
    END IF;

    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
       OR NEW.workload_id IS DISTINCT FROM OLD.workload_id
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.version_number IS DISTINCT FROM OLD.version_number
       OR NEW.digest IS DISTINCT FROM OLD.digest
       OR NEW.object_ref IS DISTINCT FROM OLD.object_ref
       OR NEW.manifest IS DISTINCT FROM OLD.manifest
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Agent workload version content is immutable';
    END IF;

    IF OLD.state = 'REGISTERED' AND NEW.state = 'ACTIVE' THEN
        IF NEW.activated_by IS NULL
           OR NEW.activated_by = OLD.created_by
           OR NEW.activated_at IS NULL
           OR NEW.revision <> OLD.revision + 1 THEN
            RAISE EXCEPTION 'Agent workload version activation requires an independent actor';
        END IF;
    ELSIF OLD.state = 'ACTIVE' AND NEW.state = 'SUPERSEDED' THEN
        IF NEW.activated_by IS DISTINCT FROM OLD.activated_by
           OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
           OR NEW.revision <> OLD.revision + 1 THEN
            RAISE EXCEPTION 'Only the active state may be superseded';
        END IF;
    ELSE
        RAISE EXCEPTION 'Invalid agent workload version state transition';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_workload_version_immutable
    BEFORE UPDATE OR DELETE ON uacrf_catalog.agent_workload_version
    FOR EACH ROW EXECUTE FUNCTION uacrf_catalog.protect_agent_workload_version();

CREATE FUNCTION uacrf_catalog.validate_agent_workload_active_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.active_version_id IS DISTINCT FROM OLD.active_version_id THEN
        IF NEW.revision <> OLD.revision + 1 THEN
            RAISE EXCEPTION 'Agent workload activation must advance its revision';
        END IF;
        IF NEW.active_version_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM uacrf_catalog.agent_workload_version version
            WHERE version.tenant_id = NEW.tenant_id
              AND version.environment_id = NEW.environment_id
              AND version.workload_id = NEW.id
              AND version.id = NEW.active_version_id
              AND version.state = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'Active workload version pointer must target an active retained version';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_workload_active_version_guard
    BEFORE UPDATE OF active_version_id ON uacrf_catalog.agent_workload
    FOR EACH ROW EXECUTE FUNCTION uacrf_catalog.validate_agent_workload_active_version();
