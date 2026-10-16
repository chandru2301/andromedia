# Andromedia: From Connected Tools to Grounded Answers

Andromedia is a local-first knowledge and intelligence layer for engineering and revenue teams. It brings source code, Git history, Slack discussions, Notion documents, Jira and Linear issues, and Zoho CRM records into one searchable Lucene index.

Instead of asking each tool separately, users can ask questions such as:

- Why did we change the authentication flow?
- Which service owns this API?
- What decisions were made during the last incident?
- Which active CRM deals need attention?
- What evidence supports this recommendation?

Andromedia retrieves relevant source material and uses a configured language model to produce a grounded answer with provenance.

## How the application works

```text
Code · Git · Slack · Notion · Jira · Linear · Zoho CRM
                           │
                           ▼
                 Ingestion and chunking
                           │
                           ▼
              Lucene text + vector index
                           │
                           ▼
              Search · Explore · Investigate
                           │
                           ▼
                    API · UI · CLI
```

Each source is converted into searchable chunks. Code remains connected to files and symbols, Slack replies remain grouped as threads, Notion pages are split by section, tickets retain issue metadata, and Zoho deals are enriched with account, contact, note, and activity context.

## What you need

For the recommended Docker setup:

- Docker and Docker Compose
- At least one supported AI provider key for generated answers
- Credentials for each external source you want to connect
- A local repository under `workspace/` if you want to index code

For local backend development:

- Java 21 or later
- Maven

The backend listens on `http://localhost:8083`.

## Start Andromedia with Docker

Run these commands from the directory containing `docker-compose.yml`:

```bash
cp .env.example .env
```

Add the provider keys you intend to use:

```dotenv
# Generated answers using Groq
GROQ_API_KEY=gsk_your_key

# OpenRouter chat and optional vector embeddings
OPENROUTER_API_KEY=sk-or-v1-your_key
OPENROUTER_HTTP_REFERER=http://localhost:5173
OPENROUTER_APP_TITLE=Andromedia

# Optional backend defaults; credentials can also be sent from the UI
SLACK_BOT_TOKEN=xoxb-your_token
NOTION_API_KEY=secret_your_key

# Optional: persist per-user integration + LLM secrets in PostgreSQL
ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED=true
ANDROMEDIA_DATABASE_URL=jdbc:postgresql://localhost:5432/andromedia
ANDROMEDIA_DATABASE_USER=andromedia
ANDROMEDIA_DATABASE_PASSWORD=andromedia
ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY=4l2v4m7xK0YdM9v7oQk7X1w0v8vQdN5QmQ0P6gY8K1M=
```

Local Postgres (when enabling the credential vault):

```bash
# From andromedia/ — starts only Postgres (API can still run via mvn)
docker compose up -d postgres

# Or full stack (API + Postgres)
docker compose up --build -d
```

Defaults: user/password/db `andromedia`, port `5432`. Copy `.env.example` → `.env` if needed.

Flyway creates `users` and `user_credentials` on startup when `ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED=true`. Secrets are AES-GCM encrypted with `ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY`. APIs: `GET/PUT/DELETE /api/v1/credentials`.

Start the API:

```bash
docker compose up --build -d
```

Confirm that it is healthy:

```bash
curl http://localhost:8083/api/v1/health
```

Expected response:

```json
{"status":"UP"}
```

Useful operations:

```bash
# Follow backend logs
docker compose logs -f andromedia-api

# Stop while preserving indexes
docker compose down

# Delete containers and indexed data
docker compose down -v
```

The Docker volume `andromedia-data` stores indexes under `/home/andromedia/.andromedia`. The local `workspace/` directory is mounted read-only at `/workspace`.

## Configure the AI provider

Andromedia supports Groq, DeepSeek, OpenRouter, and Ollama as chat providers. Groq is the
default primary provider; when configured, DeepSeek is used automatically after Groq exhausts
retries for a transient failure.

### Option 1: Environment configuration

Set the key in `.env`, then restart the backend:

```dotenv
GROQ_API_KEY=gsk_your_key
ANDROMEDIA_CHAT_PROVIDER=groq
GROQ_CHAT_MODEL=qwen/qwen3.8-27b
```

Or use OpenRouter:

```dotenv
OPENROUTER_API_KEY=sk-or-v1-your_key
ANDROMEDIA_CHAT_PROVIDER=openrouter
OPENROUTER_CHAT_MODEL=meta-llama/llama-3.3-70b-instruct
```

To configure the official DeepSeek fallback:

```dotenv
DEEPSEEK_API_KEY=sk-your_key
DEEPSEEK_CHAT_MODEL=deepseek-v4-flash
```

### Option 2: Application settings

In the UI, open **Settings**, choose a provider, select a model, enter the API key, and save.

The same configuration is available through the API:

```bash
curl -X PUT http://localhost:8083/api/v1/settings/chat-provider \
  -H 'Content-Type: application/json' \
  -d '{
    "provider": "groq",
    "model": "qwen/qwen3.8-27b",
    "apiKey": "gsk_your_key"
  }'
```

Check the active provider:

```bash
curl http://localhost:8083/api/v1/settings/chat-provider
```

API keys are never returned by the settings endpoint. A key entered at runtime is held in backend memory and must be configured again after a backend restart; use environment variables for durable deployment configuration.

### Chat and embeddings are separate

The selected chat provider generates answers. Vector embeddings use the embedding configuration, which currently defaults to OpenRouter.

- With `"embed": false`, indexing and BM25 text search work without an embedding key.
- With `"embed": true`, set `OPENROUTER_API_KEY`.
- Hybrid search falls back to text retrieval when vectors are unavailable.

Start with `"embed": false` to validate a connector, then re-index with embeddings when semantic retrieval is required.

## Connect the user interface

Start the separate Andro frontend application:

```bash
export VITE_ANDROMEDIA_API_URL=http://localhost:8083
npm run dev
```

The development UI normally opens at `http://localhost:5173`.

### Electron desktop (Trial / Local Pro)

The `andro/` Electron app gates on a welcome screen:

- **Trial** — **Sign in** / **Create account** open the system browser to `{VITE_AUTH_WEB_URL}/desktop/auth` (default `https://andro-app.vercel.app`). After Clerk auth, landing mints a short-lived sign-in ticket and redirects to `andro://auth/callback?ticket=…`. Electron redeems the ticket, then calls `POST /api/v1/trial/start` against the hosted API with `Authorization: Bearer <JWT>` and `X-Trial-Id`.
- **Local Pro** — Continue locally; `service=local`, no Bearer; talks to your local API (default `http://localhost:8083`).

```bash
cd andro
cp .env.example .env.local   # set VITE_CLERK_PUBLISHABLE_KEY, VITE_AUTH_WEB_URL, API URL
npm install
npm run electron:dev         # Vite + Electron against localhost
# Packaged AppImage (bakes env at build time):
VITE_ANDROMEDIA_API_URL=https://your-trial-host \
VITE_AUTH_WEB_URL=https://andro-app.vercel.app \
npm run electron:dist
```

**Protocol:** packaged builds register the `andro://` scheme (`electron-builder` `protocols`). Dev registers via `setAsDefaultProtocolClient`.

**Clerk dashboard:** allow the landing origin (`https://andro-app.vercel.app`) for web auth. No custom-scheme allowlist is required — the landing page issues `andro://` after minting the ticket.

Trial calls use `VITE_ANDROMEDIA_API_URL` (hosted). Local Pro always uses `VITE_LOCAL_ANDROMEDIA_API_URL` or `http://localhost:8083`, so a packaged build can bake the hosted Trial URL without breaking Continue locally.

Use the following workflow for each source:

1. Open **Sources**.
2. Choose a connector and select **Configure**.
3. Enter credentials and source identifiers.
4. Test the connection.
5. Save and start indexing.
6. Wait for the indexing job to complete.
7. Open the service overview or ask an investigation question.

Use the same `service` value for related sources. For example, indexing code, Slack, Jira, and Zoho under `customer-platform` lets Andromedia retrieve evidence from all four sources in one investigation.

## Index a code repository

Place or link the repository under `workspace/`:

```bash
ln -s /absolute/path/to/customer-platform ./workspace/customer-platform
```

Start a code indexing job:

```bash
curl -X POST http://localhost:8083/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "localPath": "/workspace/customer-platform",
    "embed": false
  }'
```

The response includes a job ID. Poll it with:

```bash
curl http://localhost:8083/api/v1/index/JOB_ID
```

Index Git history into the same service:

```bash
curl -X POST http://localhost:8083/api/v1/index/history \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "localPath": "/workspace/customer-platform",
    "embed": false
  }'
```

## Configure external sources

### Slack

Create a Slack app with a Bot User OAuth Token and invite the bot to every channel that should be indexed.

Typical scopes are:

- `channels:read`
- `channels:history`
- `groups:read` and `groups:history` for private channels
- `users:read`

Start indexing:

```bash
curl -X POST http://localhost:8083/api/v1/index/slack \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "botToken": "xoxb-your_token",
    "channels": "#backend, #incidents",
    "lookbackDays": 90,
    "embed": false
  }'
```

Slack DMs and channels where the bot is not a member are not indexed.

### Notion

Create a Notion integration, copy its secret, and explicitly share the required pages or databases with that integration.

Test access:

```bash
curl -X POST http://localhost:8083/api/v1/integrations/notion/test \
  -H 'Content-Type: application/json' \
  -d '{
    "apiKey": "secret_your_key",
    "pageIds": ["PAGE_ID"]
  }'
```

Start indexing:

```bash
curl -X POST http://localhost:8083/api/v1/index/notion \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "apiKey": "secret_your_key",
    "pageIds": ["PAGE_ID"],
    "databaseIds": [],
    "embed": false
  }'
```

A `404` usually means the page was not shared with the integration.

### Jira Cloud

Create an Atlassian API token. Jira authentication uses the site URL, account email, and API token.

```bash
curl -X POST http://localhost:8083/api/v1/index/jira \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "baseUrl": "https://your-company.atlassian.net",
    "email": "you@company.com",
    "apiToken": "your_token",
    "projectKeys": ["PLAT"],
    "lookbackDays": 90,
    "embed": false
  }'
```

Open issues are retained regardless of age. Old completed, cancelled, or resolved issues are filtered according to `lookbackDays`.

### Linear

Create a Linear personal API key and identify the team by its key or name.

```bash
curl -X POST http://localhost:8083/api/v1/index/linear \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "apiKey": "lin_api_your_key",
    "teamKeys": ["PLAT"],
    "lookbackDays": 90,
    "embed": false
  }'
```

### Zoho CRM

Zoho V1 uses a manual Self Client OAuth flow. Refresh tokens are vaulted **server-side** (AES-GCM); the browser only keeps a `connectionId`.

1. Create a **Self Client** in the Zoho API Console.
2. Generate a short-lived grant token with `ZohoCRM.modules.ALL`, or equivalent read scopes for Deals, Accounts, Contacts, Notes, Tasks, Calls, Events, and Meetings.
3. Exchange the grant token once — Andromedia stores the encrypted refresh token under `~/.andromedia/connections/`.
4. Set `ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY` to a base64-encoded 32-byte key for durable vault encryption.

Exchange the grant token:

```bash
curl -X POST http://localhost:8083/api/v1/integrations/zoho/exchange-grant \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "clientId": "1000.your_client_id",
    "clientSecret": "your_client_secret",
    "grantToken": "1000.short_lived_grant",
    "region": "com"
  }'
```

Index CRM records with the returned `connectionId` (never send the refresh token from the browser):

```bash
curl -X POST http://localhost:8083/api/v1/index/zoho \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "connectionId": "uuid-from-exchange",
    "lookbackDays": 90,
    "minQualityScore": 0.35,
    "embed": false
  }'
```

Supported regions in V1 are `com` and `in`.

For a real production organization, disable classic Zoho sample-account signatures. This avoids incorrectly classifying legitimate accounts with names that overlap Zoho sample data:

```bash
export ANDROMEDIA_ZOHO_SANDBOX_DEMO_SIGNATURES_ENABLED=false
```

Only enable this option for a known Zoho sample sandbox. Other high-confidence demo signals, such as placeholder emails, placeholder phone numbers, and explicit sample or test text, continue to work.

Zoho CRM and OAuth requests automatically retry HTTP 429, 408, and transient 5xx responses with bounded exponential backoff and `Retry-After` support.

## Verify indexed data

Check the service overview:

```bash
curl http://localhost:8083/api/v1/index/services/customer-platform/overview
```

Run a grounded investigation:

```bash
curl -X POST http://localhost:8083/api/v1/investigate \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "customer-platform",
    "query": "Why did we change the authentication flow?",
    "context": {}
  }'
```

A useful first validation sequence is:

1. Ask a question with a known answer in one indexed source.
2. Confirm that the response cites the expected source.
3. Ask a cross-source question that combines code with a ticket or discussion.
4. Re-index with `"embed": true` and compare semantic retrieval if an OpenRouter key is configured.

## Hosted deploy (App Runner + RDS + Vercel)

For the production AWS App Runner API, RDS credential vault, and Vercel landing site, see [aws-apprunner-rds-vercel.md](aws-apprunner-rds-vercel.md). Redeploy the API with `./scripts/deploy-apprunner.sh` from this directory.

Remote MCP (Streamable HTTP / SSE) for Cursor and Claude.ai: see [`../../mcp/README.md`](../../mcp/README.md) — service `andro-mcp` in `us-east-1`.

## Security and production notes

- Treat Slack, Notion, Jira, Linear, Zoho, Groq, and OpenRouter credentials as secrets.
- Do not commit `.env` or paste real credentials into documentation, issue trackers, or logs.
- Prefer environment variables or a secret manager for deployments.
- When PostgreSQL credential vault is enabled, integration and LLM API keys are stored encrypted per Clerk/`trialId` user — never returned in plaintext by `GET /api/v1/credentials`.
- The API does not yet provide application-level authentication or authorization beyond trial/Clerk guards. Do not expose port `8083` directly to the public internet.
- Put the API behind an authenticated reverse proxy or private network.
- Indexing job state is still process-local. Restarting the backend can lose active job state (saved vault credentials persist in Postgres).
- Back up the Andromedia data volume if indexed data must survive host replacement.

## Troubleshooting

### The API is unavailable

```bash
docker compose ps
docker compose logs -f andromedia-api
curl http://localhost:8083/actuator/health
```

### Generated answers report a missing API key

Configure the selected chat provider in Settings or set its environment variable, then restart the backend if `.env` changed.

### Embedding fails while chat works

Chat and embedding credentials are separate. Set `OPENROUTER_API_KEY`, or index with `"embed": false`.

### A connector test passes but no data appears

Indexing is asynchronous. Read the returned job ID and poll the matching endpoint until its status is `completed` or `failed`.

### Notion returns `404`

Share the page or database with the Notion integration, not only with a human user.

### Slack returns no channels or messages

Verify the bot scopes and invite the bot to each requested channel.

### Zoho returns unauthorized

Check the client ID, client secret, refresh token, and region. A `.com` token cannot be used against the `.in` API host, or vice versa.

## Next steps

- Read [Slack ingestion](./slack-ingestion.md)
- Read [Notion ingestion](./notion-ingestion.md)
- Read [Jira ingestion](./jira-ingestion.md)
- Read [Linear ingestion](./linear-ingestion.md)
- Read [Zoho ingestion](./zoho-ingestion.md)
- Validate Zoho retrieval with [Zoho retrieval validation](./zoho-retrieval-validation.md)

Once the first service is indexed, Andromedia is ready to answer questions across connected systems while keeping the supporting evidence visible.
