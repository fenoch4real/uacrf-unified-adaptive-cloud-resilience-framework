CREATE TABLE uacrf_catalog.service_revision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    service_id text NOT NULL,
    resource_version bigint NOT NULL CHECK (
        resource_version BETWEEN 1 AND 9007199254740991
    ),
    content_digest char(64) NOT NULL CHECK (content_digest ~ '^[a-f0-9]{64}$'),
    snapshot jsonb NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, service_id, resource_version),
    FOREIGN KEY (tenant_id, environment_id, service_id)
        REFERENCES uacrf_catalog.service (tenant_id, environment_id, id)
);

CREATE FUNCTION uacrf_catalog.protect_service_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_service_revision bigint;
    last_resource_version bigint;
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Service catalog revisions are immutable and retained';
    END IF;

    SELECT revision
      INTO current_service_revision
      FROM uacrf_catalog.service
     WHERE tenant_id = NEW.tenant_id
       AND environment_id = NEW.environment_id
       AND id = NEW.service_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'A service revision requires an existing service';
    END IF;

    SELECT COALESCE(MAX(resource_version), 0)
      INTO last_resource_version
      FROM uacrf_catalog.service_revision
     WHERE tenant_id = NEW.tenant_id
       AND environment_id = NEW.environment_id
       AND service_id = NEW.service_id;

    IF NEW.resource_version <= last_resource_version
       OR NEW.resource_version <> current_service_revision THEN
        RAISE EXCEPTION 'Service revisions must be appended at the current service revision';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER service_revision_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON uacrf_catalog.service_revision
    FOR EACH ROW EXECUTE FUNCTION uacrf_catalog.protect_service_revision();
