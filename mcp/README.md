# Andro MCP (remote)

Thin MCP adapter that proxies tools to the Andromedia HTTP API.

## Endpoints (App Runner)

| | |
|--|--|
| Service | `andro-mcp` (region `us-east-1`) |
| URL | `https://wykugmbu2n.us-east-1.awsapprunner.com` |
| Health | `GET /health` |
| Streamable HTTP | `POST/GET /mcp` |
| Legacy SSE | `GET /sse` |
| Proxies to | `https://tuuf7zapih.ap-south-1.awsapprunner.com` |

API key: AWS Secrets Manager `andro/mcp-api-key` (both `ap-south-1` and `us-east-1`).

```bash
aws secretsmanager get-secret-value --region ap-south-1 \
  --secret-id andro/mcp-api-key --query SecretString --output text
```

Send `Authorization: Bearer <key>` on `/mcp`, `/sse`, and `/messages`.

> **Note:** `ap-south-1` App Runner is limited to 2 services (`andro` + `page-pulse`), so MCP was deployed in **us-east-1**.

## Tools

- `andro_investigate`
- `andro_search`
- `andro_list_sources`
- `andro_get_file_history` — **off by default** (set `ANDRO_ENABLE_FILE_HISTORY=1`); heuristic GIT investigate by path, not a true git log; needs GIT indexed
- `search` / `fetch` — ChatGPT connector contract (deep research expects these
  two names); both wrap the same hybrid search so one URL serves both vendors.

## Desktop connector relay (local workspace → ChatGPT / Claude)

The hosted MCP above proxies to the **hosted** API. To let ChatGPT or Claude in
the browser query a workspace that lives on a user's **own machine**, the Andro
desktop app opens an outbound WebSocket to this service and the relay forwards
tool calls down it. No port forwarding, tunnel binary or firewall change.

```
Andro desktop  --outbound WSS-->  relay  <--HTTPS--  ChatGPT / Claude
      |                                                    |
localhost:8083 (their index)          https://<relay>/w/<workspaceId>/mcp?k=<key>
```

**User-facing flow — Andro desktop → Settings → "ChatGPT & Claude connector":**

1. *Turn on connector* — the app registers a workspace and holds the socket open.
2. Copy the one URL shown, or click **install** for Cursor / Claude Code / Claude Desktop.
3. Paste into ChatGPT → Connectors → Add, or Claude → Add custom connector.

That is the whole setup: no client id, secret, API key or JSON editing.

### Relay endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /pair/register` | none | desktop claims a workspace; returns `agentToken` + `connectorUrl` once |
| `GET /pair/:id/status` | `Bearer <agentToken>` | live status for the Settings panel |
| `POST /pair/:id/rotate` | `Bearer <agentToken>` | new connect key; revokes every URL handed out |
| `DELETE /pair/:id` | `Bearer <agentToken>` | unpair the machine |
| `GET /agent` (WS) | `Bearer <agentToken>` or `?t=` | the desktop agent's outbound socket |
| `POST /w/:id/mcp` | `?k=<connectKey>` or `Bearer` | Streamable HTTP MCP for browser clients |

`/w/:id/mcp` is **stateless** (one MCP server per request), so a relay restart
never strands a client and several browser clients can share one workspace.

### Relay env

| Var | Default | Meaning |
|---|---|---|
| `RELAY_ENABLED` | `true` | set `false` to serve only the hosted proxy |
| `RELAY_STATE_FILE` | *(unset)* | JSON path so connector URLs survive a redeploy |
| `RELAY_CALL_TIMEOUT_MS` | `60000` | how long a tool call waits on the desktop |

### Security model (MVP)

- The connect key is a bearer secret **in the URL** — anyone holding the URL can
  query that workspace. It is shown as secret in the UI and is rotatable.
- The desktop agent only executes `GET/POST` on `/api/v1/investigate`,
  `/api/v1/search` and `/api/v1/index/services/*/overview`. It is an allowlist,
  not a general proxy, so a compromised relay cannot drive arbitrary local HTTP.
- The `agentToken` never leaves the desktop's `userData` directory.
- Workspace registry is in memory unless `RELAY_STATE_FILE` is set — same
  demo-grade caveat as the OAuth store below.

## Claude.ai (browser) — remote connector

1. Claude → **Customize → Connectors** → **Add custom connector**
2. **Remote MCP server URL:** `https://wykugmbu2n.us-east-1.awsapprunner.com/mcp`
3. **Advanced settings — leave OAuth Client ID / Secret empty**  
   (Claude will open our `/authorize` OAuth flow automatically; do **not** paste the API key as Client ID)
4. Click **Add**, then **Connect** — browser should redirect through Andro OAuth and return to Claude
5. Enable the connector in the chat and try `andro_list_sources` / `andro_investigate`

If Connect fails with `Cannot GET /authorize`, wait ~2 minutes after a redeploy and retry (OAuth was added for Claude).

Static API key auth (Cursor / Request headers beta):

```bash
aws secretsmanager get-secret-value --region ap-south-1 \
  --secret-id andro/mcp-api-key --query SecretString --output text
```

Header: `Authorization: Bearer <key>`

## Cursor — remote URL

```json
{
  "mcpServers": {
    "andro": {
      "url": "https://wykugmbu2n.us-east-1.awsapprunner.com/mcp",
      "headers": {
        "Authorization": "Bearer <MCP_API_KEY>"
      }
    }
  }
}
```

## Cursor — local stdio (dev)

```bash
cd mcp
npm install
MCP_TRANSPORT=stdio ANDRO_BASE_URL=http://localhost:8083 node server.js
```

```json
{
  "mcpServers": {
    "andro": {
      "command": "node",
      "args": ["/absolute/path/to/andromedia/mcp/server.js"],
      "env": {
        "MCP_TRANSPORT": "stdio",
        "ANDRO_BASE_URL": "http://localhost:8083",
        "ANDRO_SERVICE": "local"
      }
    }
  }
}
```

## Retarget API (env only, no image rebuild)

```bash
./scripts/update-mcp-env.sh
# override: ANDRO_BASE_URL=https://tuuf7zapih.ap-south-1.awsapprunner.com ./scripts/update-mcp-env.sh
curl -fsS https://wykugmbu2n.us-east-1.awsapprunner.com/health
```

## Redeploy image

```bash
./scripts/deploy-mcp.sh
```

## Env vars (App Runner)

| Variable | Purpose |
|----------|---------|
| `ANDRO_BASE_URL` | Andromedia API base |
| `ANDRO_SERVICE` | Default service (`local` or trial id) |
| `ANDRO_TRIAL_ID` | Optional `X-Trial-Id` |
| `ANDRO_ACCESS_TOKEN` | Optional Clerk Bearer for trial API |
| `MCP_API_KEY` | Protects remote MCP routes |
| `MCP_TRANSPORT` | `http` (remote) or `stdio` (local) |
| `PORT` | `8080` |
