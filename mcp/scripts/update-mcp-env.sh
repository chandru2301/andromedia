#!/usr/bin/env bash
# Merge ANDRO_BASE_URL (and optional MCP_API_KEY) into App Runner service andro-mcp.
# Does not print runtime environment values.
# Requires: aws CLI, python3
set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
SERVICE_ARN="${APP_RUNNER_SERVICE_ARN:-arn:aws:apprunner:us-east-1:820175832156:service/andro-mcp/63032f9d27184e3f8d36415f01bbbcf6}"
ANDRO_BASE_URL="${ANDRO_BASE_URL:-https://tuuf7zapih.ap-south-1.awsapprunner.com}"
export MCP_API_KEY="${MCP_API_KEY:-}"

TMP="$(mktemp)"
trap 'rm -f "${TMP}" "${TMP}.update.json"' EXIT

aws apprunner describe-service --region "${REGION}" --service-arn "${SERVICE_ARN}" > "${TMP}"

python3 - <<'PY' "${TMP}" "${ANDRO_BASE_URL}" > "${TMP}.update.json"
import json, os, sys
desc_path, andro_base = sys.argv[1:3]
svc = json.load(open(desc_path))["Service"]
img = svc["SourceConfiguration"]["ImageRepository"]
env = dict(img.get("ImageConfiguration", {}).get("RuntimeEnvironmentVariables") or {})
env["ANDRO_BASE_URL"] = andro_base.rstrip("/")
if os.environ.get("MCP_API_KEY"):
    env["MCP_API_KEY"] = os.environ["MCP_API_KEY"]
payload = {
    "ServiceArn": svc["ServiceArn"],
    "SourceConfiguration": {
        "ImageRepository": {
            "ImageIdentifier": img["ImageIdentifier"],
            "ImageRepositoryType": img["ImageRepositoryType"],
            "ImageConfiguration": {
                "Port": img.get("ImageConfiguration", {}).get("Port", "8080"),
                "RuntimeEnvironmentVariables": env,
            },
        },
        "AutoDeploymentsEnabled": svc["SourceConfiguration"].get("AutoDeploymentsEnabled", False),
        "AuthenticationConfiguration": svc["SourceConfiguration"].get("AuthenticationConfiguration", {}),
    },
    "InstanceConfiguration": svc.get("InstanceConfiguration", {}),
    "HealthCheckConfiguration": svc.get("HealthCheckConfiguration", {}),
    "NetworkConfiguration": svc.get("NetworkConfiguration", {}),
}
auth = payload["SourceConfiguration"].get("AuthenticationConfiguration") or {}
if not auth.get("AccessRoleArn"):
    payload["SourceConfiguration"].pop("AuthenticationConfiguration", None)
else:
    payload["SourceConfiguration"]["AuthenticationConfiguration"] = {
        "AccessRoleArn": auth["AccessRoleArn"]
    }
json.dump(payload, sys.stdout)
PY

echo "==> Updating andro-mcp ANDRO_BASE_URL (env values not printed)"
aws apprunner update-service --region "${REGION}" --cli-input-json "file://${TMP}.update.json" \
  --query 'Service.{Status:Status,Url:ServiceUrl}' --output json

echo "==> Update submitted. When Status=RUNNING:"
echo "    curl -fsS https://wykugmbu2n.us-east-1.awsapprunner.com/health"
echo "    Expect androBaseUrl=${ANDRO_BASE_URL}"
