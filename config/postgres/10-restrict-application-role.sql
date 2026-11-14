\set ON_ERROR_STOP on

\getenv admin_user POSTGRES_USER
\getenv admin_password POSTGRES_PASSWORD
\getenv app_user APP_DB_USER
\getenv app_password APP_DB_PASSWORD
\getenv database POSTGRES_DB

SELECT :'app_user' IN (:'admin_user', current_user) AS same_role \gset
\if :same_role
\echo 'APP_DB_USER must differ from POSTGRES_USER and the connected bootstrap user'
DO $$ BEGIN
    RAISE EXCEPTION 'PostgreSQL admin and application roles must be distinct';
END $$;
\endif

SELECT format(
    'CREATE ROLE %I WITH LOGIN SUPERUSER PASSWORD %L',
    :'admin_user',
    :'admin_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'admin_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN SUPERUSER PASSWORD %L',
    :'admin_user',
    :'admin_password'
)
FROM pg_roles
WHERE rolname = :'admin_user'
  AND rolname <> current_user
\gexec

SELECT format(
    'CREATE ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'app_user'
)
\gexec

ALTER ROLE :"app_user" WITH LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

ALTER DATABASE :"database" OWNER TO :"app_user";

\if :{?legacy_user}
SELECT format('ALTER SCHEMA %I OWNER TO %I', nspname, :'app_user')
FROM pg_namespace
WHERE nspowner = (SELECT oid FROM pg_roles WHERE rolname = :'legacy_user')
  AND nspname <> 'information_schema'
  AND nspname NOT LIKE 'pg\_%' ESCAPE '\'
\gexec

SELECT format(
    'ALTER %s %I.%I OWNER TO %I',
    CASE relkind
        WHEN 'S' THEN 'SEQUENCE'
        WHEN 'v' THEN 'VIEW'
        WHEN 'm' THEN 'MATERIALIZED VIEW'
        WHEN 'f' THEN 'FOREIGN TABLE'
        ELSE 'TABLE'
    END,
    n.nspname,
    c.relname,
    :'app_user'
)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :'legacy_user')
  AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
  AND n.nspname <> 'information_schema'
  AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\'
\gexec

SELECT format('ALTER ROLE %I NOLOGIN', :'legacy_user')
WHERE :'legacy_user' <> :'admin_user'
\gexec
\endif
