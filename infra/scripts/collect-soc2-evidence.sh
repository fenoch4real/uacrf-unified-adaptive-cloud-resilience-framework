#!/usr/bin/env bash
set -euo pipefail

# Enterprise Phase E3: Automated SOC 2 (CC1-CC9), ISO 27001/42001, DORA, HIPAA, FedRAMP Technical Evidence Pack Exporter
EVIDENCE_DIR="${1:-./artifacts/soc2-evidence-pack}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${EVIDENCE_DIR}/${STAMP}"
mkdir -p "${OUT}/policies" "${OUT}/contracts"

echo "[UACRF-AUDIT] Collecting deterministic control evidence into ${OUT}"

cp policies/baseline.rego "${OUT}/policies/"
cp contracts/neo4j/ontology_catalog.json "${OUT}/contracts/"
cp contracts/traceability/requirements_traceability.json "${OUT}/contracts/"

git log -n 10 --pretty=format:'{"commit":"%H","author":"%an","date":"%aI","subject":"%s"}' > "${OUT}/recent_commits.jsonl" || true

find services -name 'TEST-*.xml' -exec cp {} "${OUT}/" \; 2>/dev/null || true

(cd "${OUT}" && (sha256sum contracts/* policies/* 2>/dev/null || shasum -a 256 contracts/* policies/*)) > "${OUT}/MANIFEST.sha256"

echo "[UACRF-AUDIT] SOC 2 / CCF technical evidence bundle ready at ${OUT}"
