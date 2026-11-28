#!/usr/bin/env bash
set -euo pipefail

# UACRF Step 3 Helper: Configure a GCP Sandbox Project for Read-Only Discovery (S03)
# Uses local `gcloud` if installed, otherwise falls back to Dockerized Google Cloud CLI.
# Usage: ./infra/scripts/setup-gcp-sandbox.sh <GCP_PROJECT_ID> [--create-sample-bucket]

PROJECT_ID="${1:-}"
CREATE_SAMPLE="${2:-}"

if [[ -z "${PROJECT_ID}" ]]; then
  echo "Usage: $0 <GCP_PROJECT_ID> [--create-sample-bucket]"
  exit 1
fi

run_gcloud() {
  if command -v gcloud >/dev/null 2>&1; then
    gcloud "$@"
  else
    mkdir -p "${HOME}/.config/gcloud"
    docker run --rm -it \
      -v "${HOME}/.config/gcloud:/root/.config/gcloud" \
      gcr.io/google.com/cloudsdktool/google-cloud-cli:slim \
      gcloud "$@"
  fi
}

echo "[UACRF-GCP] Setting active project: ${PROJECT_ID}"
run_gcloud config set project "${PROJECT_ID}"

echo "[UACRF-GCP] Enabling read-only discovery APIs (cloudasset, monitoring, logging, cloudresourcemanager)..."
run_gcloud services enable \
  cloudasset.googleapis.com \
  monitoring.googleapis.com \
  logging.googleapis.com \
  cloudresourcemanager.googleapis.com

SA_NAME="uacrf-discovery-ro"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

if ! run_gcloud iam service-accounts describe "${SA_EMAIL}" >/dev/null 2>&1; then
  echo "[UACRF-GCP] Creating read-only discovery service account: ${SA_EMAIL}"
  run_gcloud iam service-accounts create "${SA_NAME}" \
    --display-name="UACRF Read-Only Cloud Discovery"
fi

for ROLE in roles/cloudasset.viewer roles/monitoring.viewer roles/logging.viewer roles/browser; do
  echo "[UACRF-GCP] Binding read-only role ${ROLE} -> ${SA_EMAIL}"
  run_gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="${ROLE}" >/dev/null
done

if [[ "${CREATE_SAMPLE}" == "--create-sample-bucket" ]]; then
  BUCKET="gs://uacrf-sandbox-assets-${PROJECT_ID}"
  echo "[UACRF-GCP] Ensuring sample sandbox GCS bucket ${BUCKET} exists..."
  run_gcloud storage buckets create "${BUCKET}" --location=US --uniform-bucket-level-access || true
fi

echo ""
echo "[UACRF-GCP] Sandbox ready! Run S03 discovery-service connected to live GCP:"
echo "  export UACRF_DISCOVERY_GCP_ENABLED=true"
echo "  export UACRF_DISCOVERY_GCP_PROJECT=${PROJECT_ID}"
echo "  # Option A (ADC via gcloud auth application-default login):"
echo "  #   discovery-service automatically reads ~/.config/gcloud/application_default_credentials.json"
echo "  # Option B (Short-lived bearer token):"
echo "  #   export UACRF_DISCOVERY_GCP_TOKEN=\$(gcloud auth print-access-token)"
