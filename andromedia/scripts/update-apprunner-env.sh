#!/usr/bin/env bash
# Merge Postgres + vault env into App Runner service "andro" without printing secrets.
# Requires: aws CLI, python3, Secrets Manager secrets andro/rds/master + andro/credentials-encryption-key
set -euo pipefail

REGION="${AWS_REGION:-ap-south-1}"
SERVICE_ARN="${APP_RUNNER_SERVICE_ARN:-arn:aws:apprunner:ap-south-1:820175832156:service/andro/c90e6b82367a40a792cb4bcffa17066a}"
CORS_ORIGINS="${ANDRO_CORS_ORIGINS:-https://andro-app.vercel.app,http://localhost:3000}"
export GROQ_API_KEY="${GROQ_API_KEY:-}"
export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}"

RDS_JSON="$(aws secretsmanager get-secret-value --region "${REGION}" --secret-id andro/rds/master --query SecretString --output text)"
ENC_KEY="$(aws secretsmanager get-secret-value --region "${REGION}" --secret-id andro/credentials-encryption-key --query SecretString --output text)"

eval "$(python3 - <<'PY' "$RDS_JSON"
import json, os, sys
d = json.loads(sys.argv[1])
print(f"export RDS_USER={d['username']!r}")
print(f"export RDS_PASS={d['password']!r}")
print(f"export RDS_HOST={d['host']!r}")
print(f"export RDS_PORT={d.get('port', 5432)!r}")
print(f"export RDS_DB={d.get('dbname', 'andromedia')!r}")
PY
)"

JDBC_URL="jdbc:postgresql://${RDS_HOST}:${RDS_PORT}/${RDS_DB}"

TMP="$(mktemp)"
aws apprunner describe-service --region "${REGION}" --service-arn "${SERVICE_ARN}" > "${TMP}"

python3 - <<'PY' "${TMP}" "${JDBC_URL}" "${RDS_USER}" "${RDS_PASS}" "${ENC_KEY}" "${CORS_ORIGINS}" > "${TMP}.update.json"
import json, os, sys
desc_path, jdbc, user, password, enc_key, cors = sys.argv[1:7]
svc = json.load(open(desc_path))["Service"]
img = svc["SourceConfiguration"]["ImageRepository"]
env = dict(img.get("ImageConfiguration", {}).get("RuntimeEnvironmentVariables") or {})
env.update({
    "ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED": "true",
    "ANDROMEDIA_DATABASE_URL": jdbc,
    "ANDROMEDIA_DATABASE_USER": user,
    "ANDROMEDIA_DATABASE_PASSWORD": password,
    "ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY": enc_key,
    "ANDROMEDIA_CREDENTIALS_ALLOW_EPHEMERAL_KEY": "false",
    "ANDRO_CORS_ORIGINS": cors,
    "ANDRO_TRIAL_ENABLED": env.get("ANDRO_TRIAL_ENABLED", "true"),
    "ANDRO_TRIAL_REQUIRE_CLERK_JWT": env.get("ANDRO_TRIAL_REQUIRE_CLERK_JWT", "true"),
})
if os.environ.get("GROQ_API_KEY"):
    env["GROQ_API_KEY"] = os.environ["GROQ_API_KEY"]
if os.environ.get("DEEPSEEK_API_KEY"):
    env["DEEPSEEK_API_KEY"] = os.environ["DEEPSEEK_API_KEY"]
payload = {
    "ServiceArn": svc["ServiceArn"],
    "SourceConfiguration": {
        "ImageRepository": {
            "ImageIdentifier": img["ImageIdentifier"],
            "ImageRepositoryType": img["ImageRepositoryType"],
            "ImageConfiguration": {
                "Port": img.get("ImageConfiguration", {}).get("Port", "8083"),
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
# Drop empty auth if missing AccessRoleArn
auth = payload["SourceConfiguration"].get("AuthenticationConfiguration") or {}
if not auth.get("AccessRoleArn"):
    payload["SourceConfiguration"].pop("AuthenticationConfiguration", None)
else:
    payload["SourceConfiguration"]["AuthenticationConfiguration"] = {
        "AccessRoleArn": auth["AccessRoleArn"]
    }
json.dump(payload, sys.stdout)
PY

echo "==> Updating App Runner env (Postgres vault + CORS)"
aws apprunner update-service --region "${REGION}" --cli-input-json "file://${TMP}.update.json" \
  --query 'Service.{Status:Status,Url:ServiceUrl}' --output json

rm -f "${TMP}" "${TMP}.update.json"
echo "==> Update submitted. Wait until Status=RUNNING, then check /api/v1/health"
