# AWS App Runner + RDS + Vercel deploy guide

Production layout for Andro:

| Piece | Resource |
|-------|----------|
| Backend API | App Runner service **`andro`** — `https://tuuf7zapih.ap-south-1.awsapprunner.com` |
| Container image | ECR **`andro-backend`** — `820175832156.dkr.ecr.ap-south-1.amazonaws.com/andro-backend` |
| Postgres (credentials vault) | RDS **`andro-credits-rds`** — `andro-credits-rds.cvuwcw0sqe4r.ap-south-1.rds.amazonaws.com:5432` |
| Frontend | Vercel project **`andro`** (root `landing/`) — prod `https://andro-app.vercel.app` |
| Region | `ap-south-1` |

Secrets (never commit):

- `andro/rds/master` — JSON with `username`, `password`, `host`, `port`, `dbname`
- `andro/credentials-encryption-key` — AES key string for the credential vault

```bash
aws secretsmanager get-secret-value --region ap-south-1 --secret-id andro/rds/master --query SecretString --output text
aws secretsmanager get-secret-value --region ap-south-1 --secret-id andro/credentials-encryption-key --query SecretString --output text
```

Build context is always the **`andromedia/`** directory (this module’s `Dockerfile`), not the monorepo root.

---

## 1. Redeploy App Runner with current code

### Path B — ECR image (what production uses)

```bash
# From andromedia/
./scripts/deploy-apprunner.sh
```

What the script does:

1. `docker build` from `andromedia/`
2. Push to `andro-backend:latest` (and git SHA tag when available)
3. `aws apprunner start-deployment` for service `andro`

Overrides:

```bash
AWS_REGION=ap-south-1 \
ECR_REPO=andro-backend \
APP_RUNNER_SERVICE_ARN=arn:aws:apprunner:ap-south-1:820175832156:service/andro/bea9dc3dc61043d6b3eb0c0c14b03189 \
./scripts/deploy-apprunner.sh
```

### Path A — GitHub source

Only if the App Runner service is switched to a source-code connection. Current service uses **ECR**, so Path B applies.

### App Runner port / health

- Container port: **8083**
- Health check: TCP on 8083 (configured on the service)
- App health: `GET /api/v1/health`

---

## 2. Amazon RDS Postgres

Existing instance: **`andro-credits-rds`** (Postgres).

Credential vault needs:

- Database name: `andromedia` (create once; see below)
- User: master user `androadmin` (or a dedicated app user)
- JDBC URL: `jdbc:postgresql://andro-credits-rds.cvuwcw0sqe4r.ap-south-1.rds.amazonaws.com:5432/andromedia`

### Connectivity note (App Runner + private RDS)

App Runner **`andro`** currently uses **DEFAULT egress** (not a VPC connector). The VPC has an Internet Gateway but **no NAT**, so a VPC connector would break outbound LLM calls unless you add NAT.

**Demo / current approach:** RDS is publicly reachable on **5432**, security group allows App Runner egress. Prefer tightening later with:

1. Private subnets + NAT gateway
2. App Runner VPC connector in those subnets
3. RDS private + SG only from the connector SG

### Create the `andromedia` database (one-time)

```bash
# Pull password from Secrets Manager
export PGPASSWORD="$(aws secretsmanager get-secret-value --region ap-south-1 \
  --secret-id andro/rds/master --query SecretString --output text \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["password"])')"

psql "host=andro-credits-rds.cvuwcw0sqe4r.ap-south-1.rds.amazonaws.com \
  port=5432 user=androadmin dbname=postgres sslmode=require" \
  -c 'CREATE DATABASE andromedia;'
```

Without local `psql`:

```bash
export PGPASSWORD=... # from secret
docker run --rm -e PGPASSWORD postgres:16-alpine \
  psql "host=andro-credits-rds.cvuwcw0sqe4r.ap-south-1.rds.amazonaws.com port=5432 user=androadmin dbname=postgres sslmode=require" \
  -c 'CREATE DATABASE andromedia;'
```

Flyway creates `users` / `user_credentials` on API boot when Postgres credentials are enabled.

---

## 3. App Runner environment variables

Set on the **image configuration** (console → Service → Configuration → Configure service → Environment variables), or via `update-service`.

Required for credentials vault + trial:

| Variable | Purpose |
|----------|---------|
| `ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED` | `true` |
| `ANDROMEDIA_DATABASE_URL` | `jdbc:postgresql://andro-credits-rds.cvuwcw0sqe4r.ap-south-1.rds.amazonaws.com:5432/andromedia` |
| `ANDROMEDIA_DATABASE_USER` | `androadmin` |
| `ANDROMEDIA_DATABASE_PASSWORD` | from Secrets Manager `andro/rds/master` |
| `ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY` | from Secrets Manager `andro/credentials-encryption-key` |
| `ANDROMEDIA_CREDENTIALS_ALLOW_EPHEMERAL_KEY` | `false` in production |
| `ANDRO_TRIAL_ENABLED` | `true` |
| `SPRING_PROFILES_ACTIVE` | `trial` (optional if env already enables trial) |
| `ANDRO_CORS_ORIGINS` | `https://andro-app.vercel.app` plus any Vercel preview aliases you use |
| `ANDRO_CLERK_JWKS_URL` | Clerk JWKS URL |
| `ANDRO_CLERK_ISSUER` | Clerk issuer |
| `ANDRO_TRIAL_REQUIRE_CLERK_JWT` | `true` |
| `OPENROUTER_API_KEY` / chat model / referer | Investigate + embeddings |
| `GROQ_API_KEY` | Optional alternate chat provider |

After changing env vars, App Runner creates a new deployment automatically when you save configuration.

Helper to patch runtime env without printing secrets:

```bash
./scripts/update-apprunner-env.sh
```

---

## 4. Frontend on Vercel

- **Root Directory:** `landing`
- **Framework:** Next.js
- **Production API:** `https://tuuf7zapih.ap-south-1.awsapprunner.com`

### Environment variables (Vercel → Project → Settings → Environment Variables)

| Variable | Value |
|----------|--------|
| `NEXT_PUBLIC_ANDROMEDIA_API_URL` | `https://tuuf7zapih.ap-south-1.awsapprunner.com` |
| `NEXT_PUBLIC_SITE_URL` | `https://andro-app.vercel.app` (or your custom domain) |
| `NEXT_PUBLIC_ANDRO_DEV_MODE` | unset / `false` |
| `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY` | Clerk publishable key |
| `CLERK_SECRET_KEY` | Clerk secret key |
| `RESEND_API_KEY` | Optional waitlist/feedback |
| `FEEDBACK_TO_EMAIL` | Optional |

CLI deploy (from monorepo root):

```bash
npm i -g vercel
cd landing
vercel link   # select/create project, set Root Directory=landing if linking from parent
vercel env pull .env.local   # optional
vercel --prod
```

Or: Vercel Dashboard → Import Git repo → set Root Directory to `landing` → Deploy.

See also [landing/docs/vercel-deploy.md](../../landing/docs/vercel-deploy.md).

---

## 5. Glue / smoke checklist

1. **API health**

   ```bash
   curl -fsS https://tuuf7zapih.ap-south-1.awsapprunner.com/api/v1/health
   ```

2. **New code live** — credentials route exists (expects auth in trial mode):

   ```bash
   curl -sI https://tuuf7zapih.ap-south-1.awsapprunner.com/api/v1/credentials | head -5
   ```

3. **App Runner logs** — no JDBC / Flyway errors after Postgres env is set.

4. **CORS** — `ANDRO_CORS_ORIGINS` includes the exact Vercel origin (`https://andro-app.vercel.app`).

5. **Clerk** — add the Vercel domain under Allowed origins / redirect URLs.

6. **End-to-end** — open Vercel site → sign in → Configure → save integration secrets → refresh → secrets still “saved” (not retyped) → Investigate returns evidence.

### Rollback

```bash
# Redeploy a previous ECR tag (list images first)
aws ecr describe-images --region ap-south-1 --repository-name andro-backend \
  --query 'sort_by(imageDetails,& imagePushedAt)[-5:].imageTags'

# Point service at a prior tag via console, or retag and start-deployment
```

---

## Out of scope / follow-ups

- Lucene indexes on App Runner are **ephemeral** unless you attach durable storage (e.g. EFS) later.
- Prefer moving RDS to private + VPC connector + NAT once outbound LLM traffic is solved.
- Prefer App Runner **Secrets** references to Secrets Manager instead of plaintext env vars when you tighten security.

---

## Remote MCP (App Runner us-east-1)

`ap-south-1` is limited to two App Runner services, so the MCP adapter runs in **us-east-1**:

| | |
|--|--|
| Service | `andro-mcp` |
| URL | https://wykugmbu2n.us-east-1.awsapprunner.com |
| Health | `/health` |
| MCP | `/mcp` (Streamable HTTP), `/sse` (legacy) |
| Proxies to | https://tuuf7zapih.ap-south-1.awsapprunner.com |
| Auth | `Authorization: Bearer` from Secrets Manager `andro/mcp-api-key` |

Retarget API URL (no image rebuild): `mcp/scripts/update-mcp-env.sh`  
Redeploy image: `mcp/scripts/deploy-mcp.sh`  
Full client setup: [`mcp/README.md`](../../mcp/README.md)
