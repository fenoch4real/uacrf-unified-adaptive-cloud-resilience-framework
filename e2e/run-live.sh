#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="$repo_root/e2e/compose.yaml"
runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/uacrf-e2e.XXXXXX")"
key_dir="$runtime_dir/keys"
pem_key="$key_dir/delegation.pem"
private_key="$key_dir/delegation-private.der"
public_key="$key_dir/delegation-public.der"
s02_pem_key="$key_dir/s02-owner-receipt.pem"
s02_private_key="$key_dir/s02-owner-receipt-private.der"
s02_public_key="$key_dir/s02-owner-receipt-public.der"
env_file="$runtime_dir/compose.env"
project_name="uacrf-e2e-$(openssl rand -hex 5)"
compose_started=0
mkdir "$key_dir"
umask 077

cleanup() {
  result=$?
  trap - EXIT
  if [[ "$compose_started" == 1 ]] &&
      ! docker compose --env-file "$env_file" -p "$project_name" -f "$compose_file" down \
        --volumes --remove-orphans >/dev/null; then
    printf 'Failed to remove disposable Compose project %s.\n' "$project_name" >&2
    result=1
  fi
  rm -f \
    "$env_file" "$pem_key" "$private_key" "$public_key" \
    "$s02_pem_key" "$s02_private_key" "$s02_public_key"
  if ! rmdir "$key_dir" "$runtime_dir"; then
    printf 'Failed to remove temporary E2E credential files in %s.\n' "$runtime_dir" >&2
    result=1
  fi
  exit "$result"
}
trap cleanup EXIT

if [[ ! -x "$repo_root/e2e/node_modules/.bin/playwright" ]]; then
  printf 'Playwright dependencies are missing; run npm ci in %s/e2e first.\n' "$repo_root" >&2
  exit 1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$pem_key"
openssl pkcs8 -topk8 -nocrypt -in "$pem_key" -outform DER -out "$private_key"
openssl pkey -in "$pem_key" -pubout -outform DER -out "$public_key"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$s02_pem_key"
openssl pkcs8 -topk8 -nocrypt -in "$s02_pem_key" -outform DER -out "$s02_private_key"
openssl pkey -in "$s02_pem_key" -pubout -outform DER -out "$s02_public_key"
rm -f "$pem_key" "$s02_pem_key"

identity_admin_password="$(openssl rand -hex 24)"
identity_runtime_password="$(openssl rand -hex 24)"
catalog_admin_password="$(openssl rand -hex 24)"
catalog_runtime_password="$(openssl rand -hex 24)"
connections_admin_password="$(openssl rand -hex 24)"
connections_runtime_password="$(openssl rand -hex 24)"
keycloak_admin_password="$(openssl rand -hex 24)"
e2e_admin_password="$(openssl rand -hex 24)"
e2e_approver_password="$(openssl rand -hex 24)"
directory_client_secret="$(openssl rand -hex 24)"
s03_client_secret="$(openssl rand -hex 24)"
s07_client_secret="$(openssl rand -hex 24)"
s08_client_secret="$(openssl rand -hex 24)"
admin_expires_at="$(node -e 'console.log(new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString())')"

{
  printf 'IDENTITY_DB_ADMIN_USER=uacrf_identity_admin\n'
  printf 'IDENTITY_DB_ADMIN_PASSWORD=%s\n' "$identity_admin_password"
  printf 'IDENTITY_DB_USER=uacrf_identity_runtime\n'
  printf 'IDENTITY_DB_PASSWORD=%s\n' "$identity_runtime_password"
  printf 'CATALOG_DB_ADMIN_USER=uacrf_catalog_admin\n'
  printf 'CATALOG_DB_ADMIN_PASSWORD=%s\n' "$catalog_admin_password"
  printf 'CATALOG_DB_USER=uacrf_catalog_runtime\n'
  printf 'CATALOG_DB_PASSWORD=%s\n' "$catalog_runtime_password"
  printf 'CONNECTIONS_DB_ADMIN_USER=uacrf_connections_admin\n'
  printf 'CONNECTIONS_DB_ADMIN_PASSWORD=%s\n' "$connections_admin_password"
  printf 'CONNECTIONS_DB_USER=uacrf_connections_runtime\n'
  printf 'CONNECTIONS_DB_PASSWORD=%s\n' "$connections_runtime_password"
  printf 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin\n'
  printf 'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$keycloak_admin_password"
  printf 'KEYCLOAK_LOCAL_ADMIN_PASSWORD=%s\n' "$e2e_admin_password"
  printf 'KEYCLOAK_DIRECTORY_CLIENT_SECRET=%s\n' "$directory_client_secret"
  printf 'KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET=%s\n' "$s03_client_secret"
  printf 'KEYCLOAK_S07_EVALUATOR_CLIENT_SECRET=%s\n' "$s07_client_secret"
  printf 'KEYCLOAK_S08_RETRIEVER_CLIENT_SECRET=%s\n' "$s08_client_secret"
  printf 'IDENTITY_BOOTSTRAP_ADMIN_EXPIRES_AT=%s\n' "$admin_expires_at"
  printf 'UACRF_DELEGATION_PRIVATE_KEY_BASE64=%s\n' "$(base64 < "$private_key" | tr -d '\n')"
  printf 'UACRF_DELEGATION_PUBLIC_KEY_BASE64=%s\n' "$(base64 < "$public_key" | tr -d '\n')"
  printf 'UACRF_S02_OWNER_RECEIPT_PRIVATE_KEY_BASE64=%s\n' "$(base64 < "$s02_private_key" | tr -d '\n')"
  printf 'UACRF_S02_OWNER_RECEIPT_PUBLIC_KEY_BASE64=%s\n' "$(base64 < "$s02_public_key" | tr -d '\n')"
} > "$env_file"

compose=(docker compose --env-file "$env_file" -p "$project_name" -f "$compose_file")
"${compose[@]}" config --quiet

wait_for_url() {
  local service="$1"
  local url="$2"
  local attempt
  for attempt in $(seq 1 120); do
    if [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 2 "$url" 2>/dev/null || true)" == 2* ]]; then
      printf '%s is ready.\n' "$service"
      return 0
    fi
    sleep 1
  done
  printf '%s did not become ready at %s.\n' "$service" "$url" >&2
  "${compose[@]}" logs --tail=80 "$service" >&2
  return 1
}

printf 'Starting isolated browser E2E stack %s.\n' "$project_name"
compose_started=1
if ! "${compose[@]}" up --detach --build; then
  "${compose[@]}" logs --tail=100 identity-db >&2
  exit 1
fi
wait_for_url keycloak 'http://localhost:8180/realms/uacrf/.well-known/openid-configuration'
wait_for_url identity 'http://localhost:8082/actuator/health'
wait_for_url estate-catalog 'http://localhost:8083/actuator/health'
wait_for_url connections 'http://localhost:8085/actuator/health'
wait_for_url api-edge 'http://localhost:8080/actuator/health'
wait_for_url experience-api 'http://localhost:8081/actuator/health'
wait_for_url web 'http://localhost:8084/actuator/health'

cd "$repo_root/e2e"
KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin \
KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
UACRF_E2E_ADMIN_PASSWORD="$e2e_admin_password" \
UACRF_E2E_APPROVER_PASSWORD="$e2e_approver_password" \
  npm test
