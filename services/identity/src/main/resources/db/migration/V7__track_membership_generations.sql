LOCK TABLE uacrf_identity.membership IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE uacrf_identity.membership_generation (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL,
    service_scope text NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    PRIMARY KEY (tenant_id, environment_id, principal_id, service_scope)
);

INSERT INTO uacrf_identity.membership_generation
    (tenant_id, environment_id, principal_id, service_scope, generation)
SELECT tenant_id, environment_id, principal_id, service_scope, 1
FROM uacrf_identity.membership
GROUP BY tenant_id, environment_id, principal_id, service_scope;

CREATE FUNCTION uacrf_identity.advance_membership_generation(
    target_tenant text, target_environment text, target_principal text, target_scope text)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO uacrf_identity.membership_generation
        (tenant_id, environment_id, principal_id, service_scope, generation)
    VALUES (target_tenant, target_environment, target_principal, target_scope, 1)
    ON CONFLICT (tenant_id, environment_id, principal_id, service_scope)
    DO UPDATE SET generation = uacrf_identity.membership_generation.generation + 1;
$$;

CREATE FUNCTION uacrf_identity.track_membership_generation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM uacrf_identity.advance_membership_generation(
            NEW.tenant_id, NEW.environment_id, NEW.principal_id, NEW.service_scope);
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM uacrf_identity.advance_membership_generation(
            OLD.tenant_id, OLD.environment_id, OLD.principal_id, OLD.service_scope);
    ELSIF (OLD.tenant_id, OLD.environment_id, OLD.principal_id, OLD.service_scope)
          IS NOT DISTINCT FROM
          (NEW.tenant_id, NEW.environment_id, NEW.principal_id, NEW.service_scope) THEN
        PERFORM uacrf_identity.advance_membership_generation(
            NEW.tenant_id, NEW.environment_id, NEW.principal_id, NEW.service_scope);
    ELSE
        FOR target IN
            SELECT tenant_id, environment_id, principal_id, service_scope
            FROM (VALUES
                (OLD.tenant_id, OLD.environment_id, OLD.principal_id, OLD.service_scope),
                (NEW.tenant_id, NEW.environment_id, NEW.principal_id, NEW.service_scope)
            ) AS scopes(tenant_id, environment_id, principal_id, service_scope)
            ORDER BY tenant_id, environment_id, principal_id, service_scope
        LOOP
            PERFORM uacrf_identity.advance_membership_generation(
                target.tenant_id, target.environment_id, target.principal_id, target.service_scope);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER membership_generation_after_change
    AFTER INSERT OR UPDATE OR DELETE ON uacrf_identity.membership
    FOR EACH ROW EXECUTE FUNCTION uacrf_identity.track_membership_generation();
