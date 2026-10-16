#!/usr/bin/env bash
# Build andromedia/ Dockerfile, push to ECR, start App Runner deployment.
# Usage (from repo root or andromedia/):
#   ./scripts/deploy-apprunner.sh
# Optional env overrides:
#   AWS_REGION=ap-south-1
#   ECR_REPO=andro-backend
#   APP_RUNNER_SERVICE_ARN=arn:aws:apprunner:...
#   IMAGE_TAG=latest   # also tags with git short SHA when available

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROMEDIA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REGION="${AWS_REGION:-ap-south-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ECR_REPO="${ECR_REPO:-andro-backend}"
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
SERVICE_ARN="${APP_RUNNER_SERVICE_ARN:-arn:aws:apprunner:ap-south-1:820175832156:service/andro/c90e6b82367a40a792cb4bcffa17066a}"

if command -v git >/dev/null 2>&1 && git -C "${ANDROMEDIA_DIR}" rev-parse --short HEAD >/dev/null 2>&1; then
  SHA_TAG="$(git -C "${ANDROMEDIA_DIR}/.." rev-parse --short HEAD 2>/dev/null || git -C "${ANDROMEDIA_DIR}" rev-parse --short HEAD)"
else
  SHA_TAG=""
fi

echo "==> Logging in to ECR ${REGION}"
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "==> Building image from ${ANDROMEDIA_DIR}"
docker build -t "${ECR_REPO}:${IMAGE_TAG}" "${ANDROMEDIA_DIR}"
docker tag "${ECR_REPO}:${IMAGE_TAG}" "${ECR_URI}:${IMAGE_TAG}"
if [[ -n "${SHA_TAG}" ]]; then
  docker tag "${ECR_REPO}:${IMAGE_TAG}" "${ECR_URI}:${SHA_TAG}"
fi

echo "==> Pushing ${ECR_URI}:${IMAGE_TAG}"
docker push "${ECR_URI}:${IMAGE_TAG}"
if [[ -n "${SHA_TAG}" ]]; then
  echo "==> Pushing ${ECR_URI}:${SHA_TAG}"
  docker push "${ECR_URI}:${SHA_TAG}"
fi

echo "==> Starting App Runner deployment for ${SERVICE_ARN}"
aws apprunner start-deployment --region "${REGION}" --service-arn "${SERVICE_ARN}"

echo "==> Done. Watch status with:"
echo "  aws apprunner describe-service --region ${REGION} --service-arn ${SERVICE_ARN} --query 'Service.Status'"
echo "Health: https://tuuf7zapih.ap-south-1.awsapprunner.com/api/v1/health"
