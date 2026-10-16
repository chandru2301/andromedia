#!/usr/bin/env bash
# Build mcp/ image, push to ECR (us-east-1), start App Runner deployment.
# To change ANDRO_BASE_URL only, use ./update-mcp-env.sh (no rebuild).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REGION="${AWS_REGION:-us-east-1}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
ECR_REPO="${ECR_REPO:-andro-mcp}"
ECR_URI="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO}"
SERVICE_ARN="${APP_RUNNER_SERVICE_ARN:-arn:aws:apprunner:us-east-1:820175832156:service/andro-mcp/63032f9d27184e3f8d36415f01bbbcf6}"

echo "==> ECR login ${REGION}"
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

echo "==> Build ${MCP_DIR}"
docker build -t "${ECR_REPO}:latest" "${MCP_DIR}"
docker tag "${ECR_REPO}:latest" "${ECR_URI}:latest"
docker push "${ECR_URI}:latest"

echo "==> App Runner start-deployment"
aws apprunner start-deployment --region "${REGION}" --service-arn "${SERVICE_ARN}"
echo "Health: https://wykugmbu2n.us-east-1.awsapprunner.com/health"
