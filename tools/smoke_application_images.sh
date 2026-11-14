#!/usr/bin/env bash
set -euo pipefail

image_tag="${1:-local}"
postgres_image="postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
smoke_root="$(mktemp -d "${TMPDIR:-/tmp}/uacrf-image-smoke.XXXXXX")"
smoke_id="${smoke_root##*.}"
service_network="uacrf-image-smoke-${smoke_id}-service"
identity_network="uacrf-image-smoke-${smoke_id}-identity"
catalog_network="uacrf-image-smoke-${smoke_id}-catalog"
connections_network="uacrf-image-smoke-${smoke_id}-connections"
containers=()
networks=()
umask 077

cleanup() {
  local exit_code=$?
  trap - EXIT
  for container in "${containers[@]}"; do
    if docker container inspect "$container" >/dev/null 2>&1; then
      docker container rm --force "$container" >/dev/null
    fi
  done
  for network in "${networks[@]}"; do
    if docker network inspect "$network" >/dev/null 2>&1; then
      docker network rm "$network" >/dev/null
    fi
  done
  rm -f \
    "$smoke_root/private.pem" \
    "$smoke_root/private.der" \
    "$smoke_root/public.der" \
    "$smoke_root/identity.env" \
    "$smoke_root/catalog.env" \
    "$smoke_root/connections.env" \
    "$smoke_root/api-edge.env" \
    "$smoke_root/experience-api.env" \
    "$smoke_root/web.env"
  rmdir "$smoke_root"
  exit "$exit_code"
}
trap cleanup EXIT

wait_for_database() {
  local container="$1"
  local health
  for attempt in $(seq 1 60); do
    health="$(docker container inspect "$container" --format '{{.State.Health.Status}}')"
    if [[ "$health" == healthy ]]; then
      return 0
    fi
    if [[ "$health" == unhealthy ]]; then
      docker logs "$container" >&2
      return 1
    fi
    sleep 1
  done
  docker logs "$container" >&2
  return 1
}

verify_legacy_role_upgrade() {
  local container="$1"
  local database="$2"
  local admin_user="$3"
  local user="$4"
  local password="$5"
  local legacy_user="${database}_legacy_bootstrap"
  local upgraded_admin="${database}_migrated_admin"
  local migrated_admin_password="migration-admin-password"
  local ownership
  local legacy_login
  local admin_flags
  local rejected_admin_exists

  docker exec "$container" psql \
    --username "$admin_user" \
    --dbname "$database" \
    --set=ON_ERROR_STOP=1 \
    --command \
      "CREATE ROLE ${legacy_user} WITH LOGIN SUPERUSER; \
       ALTER DATABASE ${database} OWNER TO ${legacy_user}; \
       SET ROLE ${legacy_user}; \
       CREATE SCHEMA ${database} AUTHORIZATION ${legacy_user}; \
       CREATE TABLE ${database}.role_upgrade_probe (value text NOT NULL); \
       INSERT INTO ${database}.role_upgrade_probe VALUES ('preserved')"

  if docker exec \
      --env "POSTGRES_USER=${upgraded_admin}" \
      --env "POSTGRES_PASSWORD=${migrated_admin_password}" \
      --env "APP_DB_USER=${upgraded_admin}" \
      --env "APP_DB_PASSWORD=${password}" \
      "$container" sh -ec '
        psql --set=ON_ERROR_STOP=1 --set=legacy_user="$1" \
          --username "$1" \
          --dbname "$POSTGRES_DB" \
          --file /docker-entrypoint-initdb.d/10-restrict-application-role.sql
      ' sh "$legacy_user" >/dev/null 2>&1; then
    printf '%s accepted identical admin and application roles\n' "$database" >&2
    return 1
  fi
  rejected_admin_exists="$(docker exec "$container" psql \
    --username "$admin_user" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${upgraded_admin}')")"
  if [[ "$rejected_admin_exists" != f ]]; then
    printf '%s created the admin role despite invalid role configuration\n' \
      "$database" >&2
    return 1
  fi

  docker exec \
    --env "POSTGRES_USER=${upgraded_admin}" \
    --env "POSTGRES_PASSWORD=${migrated_admin_password}" \
    --env "APP_DB_USER=${user}" \
    --env "APP_DB_PASSWORD=${password}" \
    "$container" sh -ec '
      psql --set=ON_ERROR_STOP=1 --set=legacy_user="$1" \
        --username "$1" \
        --dbname "$POSTGRES_DB" \
        --file /docker-entrypoint-initdb.d/10-restrict-application-role.sql
    ' sh "$legacy_user"

  ownership="$(docker exec "$container" psql \
    --username "$user" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      "SELECT pg_get_userbyid(n.nspowner) || '|' || \
              pg_get_userbyid(c.relowner) || '|' || p.value \
       FROM pg_namespace n \
       JOIN pg_class c ON c.relnamespace = n.oid \
       JOIN ${database}.role_upgrade_probe p ON true \
       WHERE n.nspname = '${database}' AND c.relname = 'role_upgrade_probe'")"
  legacy_login="$(docker exec "$container" psql \
    --username "$user" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      "SELECT rolcanlogin FROM pg_roles WHERE rolname = '${legacy_user}'")"
  admin_flags="$(docker exec "$container" psql \
    --username "$upgraded_admin" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      'SELECT rolsuper FROM pg_roles WHERE rolname = current_user')"
  if [[ "$ownership" != "${user}|${user}|preserved" || \
        "$legacy_login" != f || "$admin_flags" != t ]]; then
    printf '%s legacy role upgrade did not preserve data and transfer ownership: %s, %s, %s\n' \
      "$database" "$ownership" "$legacy_login" "$admin_flags" >&2
    return 1
  fi
  printf '%s legacy role upgrade preserved data and disabled the old bootstrap login\n' \
    "$database"
}

start_database() {
  local alias="$1"
  local database="$2"
  local admin_user="$3"
  local admin_password="$4"
  local user="$5"
  local password="$6"
  local network="$7"
  local container="uacrf-image-smoke-${smoke_id}-${alias}"
  local role_flags
  local admin_flags
  containers+=("$container")
  docker run --detach \
    --name "$container" \
    --network "$network" \
    --network-alias "$alias" \
    --health-cmd="pg_isready -U ${admin_user} -d ${database}" \
    --health-interval=2s \
    --health-timeout=3s \
    --health-retries=30 \
    --volume \
      "$repo_root/config/postgres/10-restrict-application-role.sql:/docker-entrypoint-initdb.d/10-restrict-application-role.sql:ro" \
    --env "POSTGRES_DB=${database}" \
    --env "POSTGRES_USER=${admin_user}" \
    --env "POSTGRES_PASSWORD=${admin_password}" \
    --env "APP_DB_USER=${user}" \
    --env "APP_DB_PASSWORD=${password}" \
    "$postgres_image" >/dev/null
  wait_for_database "$container"
  role_flags="$(docker exec "$container" psql \
    --username "$user" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      'SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user')"
  admin_flags="$(docker exec "$container" psql \
    --username "$admin_user" \
    --dbname "$database" \
    --tuples-only \
    --no-align \
    --command \
      'SELECT rolsuper FROM pg_roles WHERE rolname = current_user')"
  if [[ "$role_flags" != f || "$admin_flags" != t ]]; then
    printf '%s database application role has unexpected elevated privileges: %s\n' \
      "$alias" "$role_flags" >&2
    return 1
  fi
  verify_legacy_role_upgrade \
    "$container" "$database" "$admin_user" "$user" "$password"
  printf '%s is ready; app role is non-superuser\n' "$alias"
}

start_application() {
  local name="$1"
  local image="$2"
  local container_port="$3"
  local env_file="$4"
  local data_network="$5"
  local container="uacrf-image-smoke-${smoke_id}-${name}"
  local run_network="$service_network"
  local mapping
  local host_port
  local response
  case "$data_network" in
    identity) run_network="$identity_network" ;;
    catalog) run_network="$catalog_network" ;;
    connections) run_network="$connections_network" ;;
    service) ;;
    *) printf 'Unknown application network: %s\n' "$data_network" >&2; return 2 ;;
  esac
  containers+=("$container")
  if [[ "$data_network" == service ]]; then
    docker run --detach \
      --name "$container" \
      --network "$run_network" \
      --network-alias "$name" \
      --publish "127.0.0.1::${container_port}" \
      --env-file "$env_file" \
      "$image" >/dev/null
  else
    docker run --detach \
      --name "$container" \
      --network "$run_network" \
      --publish "127.0.0.1::${container_port}" \
      --env-file "$env_file" \
      "$image" >/dev/null
  fi
  if [[ "$data_network" != service ]]; then
    docker network connect --alias "$name" "$service_network" "$container"
  fi
  mapping="$(docker port "$container" "${container_port}/tcp")"
  host_port="${mapping##*:}"

  for attempt in $(seq 1 60); do
    if response="$(curl --silent --fail --max-time 2 \
        "http://127.0.0.1:${host_port}/actuator/health/readiness")"; then
      printf '%s ready: %s\n' "$name" "$response"
      return 0
    fi
    if [[ "$(docker container inspect "$container" --format '{{.State.Running}}')" != true ]]; then
      docker logs "$container" >&2
      return 1
    fi
    sleep 1
  done
  docker logs "$container" >&2
  return 1
}

assert_database_isolation() {
  local container="uacrf-image-smoke-${smoke_id}-$1"
  local reachable_database="$2"
  local blocked_database="$3"
  docker run --rm \
    --network "container:${container}" \
    --entrypoint pg_isready \
    "$postgres_image" \
    -h "$reachable_database" -p 5432
  if docker run --rm \
      --network "container:${container}" \
      --entrypoint pg_isready \
      "$postgres_image" \
      -h "$blocked_database" -p 5432 >/dev/null 2>&1; then
    printf '%s unexpectedly reached %s\n' "$container" "$blocked_database" >&2
    return 1
  fi
  printf '%s can reach %s and cannot reach %s\n' \
    "$container" "$reachable_database" "$blocked_database"
}

assert_no_database_access() {
  local container="uacrf-image-smoke-${smoke_id}-$1"
  shift
  for database in "$@"; do
    if docker run --rm \
        --network "container:${container}" \
        --entrypoint pg_isready \
        "$postgres_image" \
        -h "$database" -p 5432 >/dev/null 2>&1; then
      printf '%s unexpectedly reached %s\n' "$container" "$database" >&2
      return 1
    fi
  done
  printf '%s cannot reach owner databases\n' "$container"
}

assert_cross_database_credentials_rejected() {
  local container="uacrf-image-smoke-${smoke_id}-isolation-probe"
  containers+=("$container")
  docker run --detach \
    --name "$container" \
    --network "$service_network" \
    --entrypoint sleep \
    "$postgres_image" infinity >/dev/null
  docker network connect "$identity_network" "$container"
  docker network connect "$catalog_network" "$container"
  docker network connect "$connections_network" "$container"

  docker exec "$container" pg_isready -h identity-db -p 5432 >/dev/null
  docker exec "$container" pg_isready -h catalog-db -p 5432 >/dev/null
  docker exec "$container" pg_isready -h connections-db -p 5432 >/dev/null
  docker exec --env PGPASSWORD=connections-smoke-password "$container" psql \
    --host connections-db \
    --username uacrf_connections_smoke \
    --dbname uacrf_connections \
    --set=ON_ERROR_STOP=1 \
    --command 'SELECT 1' >/dev/null
  if docker exec --env PGPASSWORD=connections-smoke-password "$container" psql \
      --host identity-db \
      --username uacrf_connections_smoke \
      --dbname uacrf_identity \
      --set=ON_ERROR_STOP=1 \
      --command 'SELECT 1' >/dev/null 2>&1; then
    printf 'connections database role unexpectedly authenticated to identity database\n' >&2
    return 1
  fi
  if docker exec --env PGPASSWORD=catalog-smoke-password "$container" psql \
      --host connections-db \
      --username uacrf_catalog_smoke \
      --dbname uacrf_connections \
      --set=ON_ERROR_STOP=1 \
      --command 'SELECT 1' >/dev/null 2>&1; then
    printf 'catalog database role unexpectedly authenticated to connections database\n' >&2
    return 1
  fi
  docker exec --env PGPASSWORD=identity-smoke-password "$container" psql \
    --host identity-db \
    --username uacrf_identity_smoke \
    --dbname uacrf_identity \
    --set=ON_ERROR_STOP=1 \
    --command 'SELECT 1' >/dev/null
  docker exec --env PGPASSWORD=catalog-smoke-password "$container" psql \
    --host catalog-db \
    --username uacrf_catalog_smoke \
    --dbname uacrf_catalog \
    --set=ON_ERROR_STOP=1 \
    --command 'SELECT 1' >/dev/null

  if docker exec --env PGPASSWORD=identity-smoke-password "$container" psql \
      --host catalog-db \
      --username uacrf_identity_smoke \
      --dbname uacrf_catalog \
      --set=ON_ERROR_STOP=1 \
      --command 'SELECT 1' >/dev/null 2>&1; then
    printf 'identity database role unexpectedly authenticated to catalog database\n' >&2
    return 1
  fi
  if docker exec --env PGPASSWORD=catalog-smoke-password "$container" psql \
      --host identity-db \
      --username uacrf_catalog_smoke \
      --dbname uacrf_identity \
      --set=ON_ERROR_STOP=1 \
      --command 'SELECT 1' >/dev/null 2>&1; then
    printf 'catalog database role unexpectedly authenticated to identity database\n' >&2
    return 1
  fi
  printf 'cross-database runtime credentials were rejected in both directions\n'
}

assert_edge_routes_reach_owners() {
  local container="uacrf-image-smoke-${smoke_id}-api-edge"
  local mapping
  local host_port
  local status
  local identity_status
  local internal_delegation_status
  local connections_status
  local evidence_status
  mapping="$(docker port "$container" 8080/tcp)"
  host_port="${mapping##*:}"
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 "http://127.0.0.1:${host_port}/internal/catalog/v1/services")"
  if [[ "$status" != 401 ]]; then
    printf 'EDGE01 catalog route returned %s instead of S02 authentication response 401\n' \
      "$status" >&2
    return 1
  fi
  printf 'EDGE01 catalog route reached S02 and preserved its unauthenticated response\n'
  connections_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 "http://127.0.0.1:${host_port}/internal/connections/v1/connections")"
  if [[ "$connections_status" != 401 ]]; then
    printf 'EDGE01 connections route returned %s instead of S03 authentication response 401\n' \
      "$connections_status" >&2
    return 1
  fi
  printf 'EDGE01 connections route reached S03 and preserved its unauthenticated response\n'
  evidence_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 "http://127.0.0.1:${host_port}/internal/evidence/v1/evidence")"
  if [[ "$evidence_status" != 401 ]]; then
    printf 'EDGE01 evidence route returned %s instead of S04 authentication response 401\n' \
      "$evidence_status" >&2
    return 1
  fi
  printf 'EDGE01 evidence route reached S04 and preserved its unauthenticated response\n'
  identity_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 "http://127.0.0.1:${host_port}/v1/me/access")"
  if [[ "$identity_status" != 401 ]]; then
    printf 'EDGE01 identity route returned %s instead of S01 authentication response 401\n' \
      "$identity_status" >&2
    return 1
  fi
  printf 'EDGE01 identity route reached S01 and preserved its unauthenticated response\n'
  internal_delegation_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --max-time 3 --request POST \
    --header 'Content-Type: application/json' \
    --data '{}' "http://127.0.0.1:${host_port}/internal/v1/delegations")"
  if [[ "$internal_delegation_status" != 404 ]]; then
    printf 'EDGE01 unexpectedly exposed S01 internal delegation route (HTTP %s)\n' \
      "$internal_delegation_status" >&2
    return 1
  fi
  printf 'EDGE01 does not expose S01 internal delegation route\n'
}

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$smoke_root/private.pem"
openssl pkcs8 -topk8 -nocrypt -in "$smoke_root/private.pem" -outform DER -out "$smoke_root/private.der"
openssl pkey -in "$smoke_root/private.pem" -pubout -outform DER -out "$smoke_root/public.der"
private_key="$(base64 < "$smoke_root/private.der" | tr -d '\r\n')"
public_key="$(base64 < "$smoke_root/public.der" | tr -d '\r\n')"

cat > "$smoke_root/identity.env" <<EOF
IDENTITY_JDBC_URL=jdbc:postgresql://identity-db:5432/uacrf_identity
IDENTITY_DB_USER=uacrf_identity_smoke
IDENTITY_DB_PASSWORD=identity-smoke-password
KEYCLOAK_ISSUER_URI=http://keycloak:8080/realms/uacrf
KEYCLOAK_JWK_SET_URI=http://keycloak:8080/realms/uacrf/protocol/openid-connect/certs
UACRF_DELEGATION_PUBLIC_KEY_BASE64=${public_key}
UACRF_DELEGATION_PRIVATE_KEY_BASE64=${private_key}
UACRF_DELEGATION_KEY_ID=image-smoke
IDENTITY_BOOTSTRAP_ENABLED=false
EOF

cat > "$smoke_root/catalog.env" <<EOF
CATALOG_JDBC_URL=jdbc:postgresql://catalog-db:5432/uacrf_catalog
CATALOG_DB_USER=uacrf_catalog_smoke
CATALOG_DB_PASSWORD=catalog-smoke-password
UACRF_DELEGATION_PUBLIC_KEY_BASE64=${public_key}
UACRF_DELEGATION_KEY_ID=image-smoke
EOF

cat > "$smoke_root/connections.env" <<EOF
CONNECTIONS_JDBC_URL=jdbc:postgresql://connections-db:5432/uacrf_connections
CONNECTIONS_DB_USER=uacrf_connections_smoke
CONNECTIONS_DB_PASSWORD=connections-smoke-password
UACRF_DELEGATION_PUBLIC_KEY_BASE64=${public_key}
UACRF_DELEGATION_KEY_ID=image-smoke
EOF

cat > "$smoke_root/api-edge.env" <<'EOF'
UACRF_EDGE_IDENTITY_BASE_URL=http://identity:8082
UACRF_EDGE_CATALOG_BASE_URL=http://estate-catalog:8083
UACRF_EDGE_CONNECTIONS_BASE_URL=http://connections:8085
UACRF_EDGE_EVIDENCE_BASE_URL=http://evidence:8086
EOF

cat > "$smoke_root/experience-api.env" <<'EOF'
UACRF_EXPERIENCE_IDENTITY_BASE_URL=http://api-edge:8080
UACRF_EXPERIENCE_INTERNAL_IDENTITY_BASE_URL=http://identity:8082
EOF

cat > "$smoke_root/web.env" <<'EOF'
UACRF_OIDC_ISSUER_URI=http://keycloak:8080/realms/uacrf
UACRF_EXPERIENCE_BASE_URL=http://experience-api:8081
UACRF_CATALOG_BASE_URL=http://api-edge:8080/internal/catalog
EOF

for network in "$service_network" "$identity_network" "$catalog_network" "$connections_network"; do
  docker network create "$network" >/dev/null
  networks+=("$network")
done

start_database identity-db uacrf_identity identity_admin identity-admin-smoke-password \
  uacrf_identity_smoke identity-smoke-password \
  "$identity_network"
start_database catalog-db uacrf_catalog catalog_admin catalog-admin-smoke-password \
  uacrf_catalog_smoke catalog-smoke-password \
  "$catalog_network"
start_database connections-db uacrf_connections connections_admin connections-admin-smoke-password \
  uacrf_connections_smoke connections-smoke-password \
  "$connections_network"

start_application identity "uacrf/identity-service:${image_tag}" 8082 \
  "$smoke_root/identity.env" identity
start_application estate-catalog "uacrf/estate-catalog-service:${image_tag}" 8083 \
  "$smoke_root/catalog.env" catalog
start_application connections "uacrf/connections-service:${image_tag}" 8085 \
  "$smoke_root/connections.env" connections
start_application api-edge "uacrf/api-edge:${image_tag}" 8080 \
  "$smoke_root/api-edge.env" service
start_application experience-api "uacrf/experience-api:${image_tag}" 8081 \
  "$smoke_root/experience-api.env" service
start_application web "uacrf/web:${image_tag}" 8084 \
  "$smoke_root/web.env" service

assert_database_isolation identity identity-db catalog-db
assert_database_isolation identity identity-db connections-db
assert_database_isolation estate-catalog catalog-db identity-db
assert_database_isolation estate-catalog catalog-db connections-db
assert_database_isolation connections connections-db identity-db
assert_database_isolation connections connections-db catalog-db
assert_no_database_access api-edge identity-db catalog-db connections-db
assert_no_database_access experience-api identity-db catalog-db connections-db
assert_no_database_access web identity-db catalog-db connections-db
assert_cross_database_credentials_rejected
assert_edge_routes_reach_owners
