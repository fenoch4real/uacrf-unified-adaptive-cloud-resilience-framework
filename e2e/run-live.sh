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
evidence_admin_password="$(openssl rand -hex 24)"
evidence_runtime_password="$(openssl rand -hex 24)"
objectives_admin_password="$(openssl rand -hex 24)"
objectives_runtime_password="$(openssl rand -hex 24)"
assessment_admin_password="$(openssl rand -hex 24)"
assessment_runtime_password="$(openssl rand -hex 24)"
keycloak_admin_password="$(openssl rand -hex 24)"
e2e_admin_password="$(openssl rand -hex 24)"
e2e_approver_password="$(openssl rand -hex 24)"
directory_client_secret="$(openssl rand -hex 24)"
s03_client_secret="$(openssl rand -hex 24)"
s03_collection_client_secret="$(openssl rand -hex 24)"
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
  printf 'EVIDENCE_DB_ADMIN_USER=uacrf_evidence_admin\n'
  printf 'EVIDENCE_DB_ADMIN_PASSWORD=%s\n' "$evidence_admin_password"
  printf 'EVIDENCE_DB_USER=uacrf_evidence_runtime\n'
  printf 'EVIDENCE_DB_PASSWORD=%s\n' "$evidence_runtime_password"
  printf 'OBJECTIVES_DB_ADMIN_USER=uacrf_objectives_admin\n'
  printf 'OBJECTIVES_DB_ADMIN_PASSWORD=%s\n' "$objectives_admin_password"
  printf 'OBJECTIVES_DB_USER=uacrf_objectives_runtime\n'
  printf 'OBJECTIVES_DB_PASSWORD=%s\n' "$objectives_runtime_password"
  printf 'ASSESSMENT_DB_ADMIN_USER=uacrf_assessment_admin\n'
  printf 'ASSESSMENT_DB_ADMIN_PASSWORD=%s\n' "$assessment_admin_password"
  printf 'ASSESSMENT_DB_USER=uacrf_assessment_runtime\n'
  printf 'ASSESSMENT_DB_PASSWORD=%s\n' "$assessment_runtime_password"
  printf 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin\n'
  printf 'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$keycloak_admin_password"
  printf 'KEYCLOAK_LOCAL_ADMIN_PASSWORD=%s\n' "$e2e_admin_password"
  printf 'KEYCLOAK_DIRECTORY_CLIENT_SECRET=%s\n' "$directory_client_secret"
  printf 'KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET=%s\n' "$s03_client_secret"
  printf 'KEYCLOAK_S03_COLLECTION_CLIENT_SECRET=%s\n' "$s03_collection_client_secret"
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
wait_for_url evidence 'http://localhost:8086/actuator/health'
wait_for_url objectives 'http://localhost:8087/actuator/health'
wait_for_url assessment 'http://localhost:8089/actuator/health'
wait_for_url api-edge 'http://localhost:8080/actuator/health'
wait_for_url experience-api 'http://localhost:8081/actuator/health'
wait_for_url web 'http://localhost:8084/actuator/health'

# Isolated, synthetic read fixtures; insertion bypasses the objective proposal workflow.
"${compose[@]}" exec -T objectives-db psql -v ON_ERROR_STOP=1 \
  -U uacrf_objectives_admin -d uacrf_objectives <<'SQL'
INSERT INTO uacrf_objectives.objective
  (tenant_id, environment_id, id, workload_id, title, metric, comparator, target,
   unit, window_seconds, origin, content_digest, state, revision, proposed_by)
VALUES
  ('local-dev', 'local', 'browser-objective-a', 'browser-objective-workload',
   'Synthetic browser objective', 'sample.latency', 'LTE', 99.5, 'ms', 60, 'LOCAL',
   '9015a54afcb9ed36f0b6967989020e2ab94b84881d82ab104f56cd4aec1d92f5',
   'DRAFT', 1, 'test-fixture'),
  ('local-dev', 'local', 'browser-objective-b', 'other-objective-workload',
   'Other synthetic objective', 'sample.latency', 'LTE', 99.5, 'ms', 60, 'LOCAL',
   '3b374d98d7f9dbe4578f776d21aa4ef6d99952ec93a9a01695b9a8674a67be3f',
   'DRAFT', 1, 'test-fixture');
SQL

# Isolated synthetic records; insertion bypasses their workflows.
"${compose[@]}" exec -T assessment-db psql -v ON_ERROR_STOP=1 \
  -U uacrf_assessment_admin -d uacrf_assessment <<'SQL'
INSERT INTO uacrf_assessment.analysis_case
  (tenant_id, environment_id, id, workload_id, content, state, revision, case_digest, created_by)
VALUES
  ('local-dev', 'local', 'browser-case-a', 'browser-case-workload',
   '{"workload_id":"browser-case-workload","purpose":"Synthetic browser assessment","metric":"sample.latency",
     "comparator":"LTE","threshold":10,"unit":"ms","window_seconds":60,
     "objective_ref":{"objective_id":"sample-objective","revision":1,"digest":"sample"},
     "graph_ref":{"graph_id":"sample-graph","revision":1,"digest":"sample"},
     "required_source_kinds":["measurement.latency"]}'::jsonb, 'SEALED', 2,
   'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'test-fixture'),
  ('local-dev', 'local', 'browser-case-b', 'other-case-workload',
   '{"workload_id":"other-case-workload","purpose":"Other synthetic assessment","metric":"sample.latency",
     "comparator":"LTE","threshold":10,"unit":"ms","window_seconds":60,
     "objective_ref":{"objective_id":"sample-objective","revision":1,"digest":"sample"},
     "graph_ref":{"graph_id":"sample-graph","revision":1,"digest":"sample"},
     "required_source_kinds":["measurement.latency"]}'::jsonb, 'DRAFT', 1, NULL, 'test-fixture');
INSERT INTO uacrf_assessment.evaluation
  (tenant_id, environment_id, id, case_id, revision, observation_cutoff,
   cutoff_epoch_second, cutoff_nano, mode, outcome, dependency_vector, result_digest)
VALUES
  ('local-dev', 'local', 'browser-evaluation-a', 'browser-case-a', 1,
   '2026-01-01T00:00:00Z', 1767225600, 0, 'SHADOW', 'INSUFFICIENT_EVIDENCE',
   '{"receipt_verification":"FAILED","receipt_reason_code":"OWNER_UNAVAILABLE","measurements":[]}'::jsonb,
   'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
  ('local-dev', 'local', 'browser-evaluation-b', 'browser-case-a', 2,
   '2026-01-02T00:00:00Z', 1767312000, 0, 'LIVE', 'CONFIRMED_BREACH',
   '{"receipt_verification":"SKIPPED","measurements":[]}'::jsonb,
   'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc');
INSERT INTO uacrf_assessment.recommendation
  (tenant_id, environment_id, id, evaluation_id, case_id, workload_id, kind,
   effect_bounds, prerequisite_refs, executable)
VALUES
  ('local-dev', 'local', 'browser-recommendation-a', 'browser-evaluation-b',
   'browser-case-a', 'browser-case-workload', 'INVESTIGATE_BREACH',
   '{"metric":"sample.latency","comparator":"LTE","threshold":10,"unit":"ms",
     "effect":"INVESTIGATE_ONLY"}'::jsonb,
   '{"case_digest":"placeholder","evaluation_digest":"placeholder"}'::jsonb, false);
INSERT INTO uacrf_assessment.incident
  (tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,
   evidence_refs,source_evaluation_ids,reason,revision,created_by)
SELECT 'local-dev','local','00000000-0000-0000-0000-' || lpad(n::text,12,'0'),
  'browser-case-workload','browser-case-a','2026-01-02T00:00:00Z',
  'Synthetic browser incident ' || n,'["sample-receipt"]'::jsonb,
  '["browser-evaluation-a"]'::jsonb,'Synthetic fixture',1,'test-fixture'
FROM generate_series(1,51) n;
INSERT INTO uacrf_assessment.learning_record
  (tenant_id,environment_id,id,incident_id,content,evidence_refs,content_digest,state,revision,proposed_by)
SELECT 'local-dev','local','10000000-0000-0000-0000-' || lpad(n::text,12,'0'),
  '00000000-0000-0000-0000-000000000001',
  '{"incident_id":"00000000-0000-0000-0000-000000000001","experiment_id":null,
    "hypotheses":["Synthetic hypothesis"],"action_items":[{"description":"Investigate sample",
      "owner_id":"sample-owner","due_at":"2026-01-03T00:00:00Z",
      "proposed_version_ref":null,"closure_test":"Check sample"}],
    "evidence_refs":["sample-receipt"],"expected_revision":1,"reason":"Synthetic fixture"}'::jsonb,
  '["sample-receipt"]'::jsonb,repeat('d',64),'PROPOSED',1,'test-fixture'
FROM generate_series(1,51) n;
SQL

cd "$repo_root/e2e"
KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME=admin \
KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
KEYCLOAK_S03_PUBLISHER_CLIENT_SECRET="$s03_client_secret" \
KEYCLOAK_S03_COLLECTION_CLIENT_SECRET="$s03_collection_client_secret" \
UACRF_E2E_ADMIN_PASSWORD="$e2e_admin_password" \
UACRF_E2E_APPROVER_PASSWORD="$e2e_approver_password" \
  npm test
