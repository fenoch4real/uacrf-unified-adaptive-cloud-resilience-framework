#!/usr/bin/env bash
set -euo pipefail

# UACRF Helper: Configure an AWS Sandbox Account for Read-Only Discovery (S03)
# Uses local `aws` CLI if installed, otherwise falls back to Dockerized amazon/aws-cli.
# Usage: ./infra/scripts/setup-aws-sandbox.sh [AWS_REGION]

REGION="${1:-us-east-1}"
ROLE_NAME="uacrf-discovery-ro"

run_aws() {
  if command -v aws >/dev/null 2>&1; then
    aws --region "${REGION}" "$@"
  else
    mkdir -p "${HOME}/.aws"
    docker run --rm \
      -v "${HOME}/.aws:/root/.aws" \
      -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
      amazon/aws-cli:2.22.0 --region "${REGION}" "$@"
  fi
}

ACCOUNT_ID="$(run_aws sts get-caller-identity --query Account --output text)"
echo "[UACRF-AWS] Verified caller account ${ACCOUNT_ID} in region ${REGION}"

echo "[UACRF-AWS] Ensuring AWS Resource Explorer index exists in ${REGION}..."
run_aws resource-explorer-2 create-index --type AGGREGATOR >/dev/null 2>&1 || true

TRUST_POLICY=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::${ACCOUNT_ID}:root" },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
)

if ! run_aws iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
  echo "[UACRF-AWS] Creating read-only discovery IAM role: ${ROLE_NAME}"
  run_aws iam create-role \
    --role-name "${ROLE_NAME}" \
    --assume-role-policy-document "${TRUST_POLICY}" \
    --description "Least-privilege read-only role for UACRF S03 discovery" >/dev/null
fi

for POLICY_ARN in \
  "arn:aws:iam::aws:policy/AWSResourceExplorerReadOnlyAccess" \
  "arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess" \
  "arn:aws:iam::aws:policy/CloudWatchLogsReadOnlyAccess" \
  "arn:aws:iam::aws:policy/job-function/ViewOnlyAccess"; do
  echo "[UACRF-AWS] Attaching ${POLICY_ARN} -> ${ROLE_NAME}"
  run_aws iam attach-role-policy --role-name "${ROLE_NAME}" --policy-arn "${POLICY_ARN}"
done

echo ""
echo "[UACRF-AWS] Sandbox ready! Run S03 discovery-service connected to live AWS via native SigV4:"
echo "  export UACRF_DISCOVERY_AWS_ENABLED=true"
echo "  export UACRF_DISCOVERY_AWS_REGION=${REGION}"
echo "  export UACRF_DISCOVERY_AWS_ROLE_ARN=arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"
echo "  # Plus either AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY env vars or ~/.aws/credentials"
