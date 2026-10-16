/**
 * Minimal in-memory OAuth for Claude.ai remote MCP (PKCE + auto client).
 * Demo-grade: tokens live in process memory (lost on redeploy).
 */
import { randomUUID, createHash } from "node:crypto";
import { mcpAuthRouter } from "@modelcontextprotocol/sdk/server/auth/router.js";
import { InvalidRequestError } from "@modelcontextprotocol/sdk/server/auth/errors.js";

const CLAUDE_REDIRECTS = [
  "https://claude.ai/api/mcp/auth_callback",
  "https://claude.com/api/mcp/auth_callback",
];

export class AndroClientsStore {
  constructor() {
    this.clients = new Map();
  }

  async getClient(clientId) {
    if (!clientId) {
      return undefined;
    }
    if (this.clients.has(clientId)) {
      return this.clients.get(clientId);
    }
    // Claude may send a static client_id without prior registration.
    return {
      client_id: clientId,
      client_id_issued_at: Math.floor(Date.now() / 1000),
      redirect_uris: [...CLAUDE_REDIRECTS],
      grant_types: ["authorization_code"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
      client_name: "Claude",
    };
  }

  async registerClient(clientMetadata) {
    const client = {
      ...clientMetadata,
      client_id: clientMetadata.client_id || randomUUID(),
      client_id_issued_at: Math.floor(Date.now() / 1000),
    };
    if (!client.redirect_uris?.length) {
      client.redirect_uris = [...CLAUDE_REDIRECTS];
    }
    this.clients.set(client.client_id, client);
    return client;
  }
}

export class AndroAuthProvider {
  constructor() {
    this.clientsStore = new AndroClientsStore();
    this.codes = new Map();
    this.tokens = new Map();
  }

  async authorize(client, params, res) {
    if (!client.redirect_uris.includes(params.redirectUri)) {
      // Allow Claude callbacks even if client metadata omitted them
      if (!CLAUDE_REDIRECTS.includes(params.redirectUri)) {
        throw new InvalidRequestError("Unregistered redirect_uri");
      }
    }
    const code = randomUUID();
    this.codes.set(code, { client, params, createdAt: Date.now() });
    const searchParams = new URLSearchParams({ code });
    if (params.state !== undefined) {
      searchParams.set("state", params.state);
    }
    const targetUrl = new URL(params.redirectUri);
    targetUrl.search = searchParams.toString();
    res.redirect(302, targetUrl.toString());
  }

  async challengeForAuthorizationCode(_client, authorizationCode) {
    const codeData = this.codes.get(authorizationCode);
    if (!codeData) {
      throw new Error("Invalid authorization code");
    }
    return codeData.params.codeChallenge;
  }

  async exchangeAuthorizationCode(client, authorizationCode, _codeVerifier) {
    const codeData = this.codes.get(authorizationCode);
    if (!codeData) {
      throw new Error("Invalid authorization code");
    }
    if (codeData.client.client_id !== client.client_id) {
      throw new Error("Authorization code was not issued to this client");
    }
    this.codes.delete(authorizationCode);
    const token = randomUUID();
    this.tokens.set(token, {
      token,
      clientId: client.client_id,
      scopes: codeData.params.scopes || ["mcp:tools"],
      expiresAt: Date.now() + 24 * 3600 * 1000,
    });
    return {
      access_token: token,
      token_type: "bearer",
      expires_in: 86400,
      scope: (codeData.params.scopes || ["mcp:tools"]).join(" "),
    };
  }

  async exchangeRefreshToken() {
    throw new Error("Refresh tokens not supported");
  }

  async verifyAccessToken(token) {
    const tokenData = this.tokens.get(token);
    if (!tokenData || tokenData.expiresAt < Date.now()) {
      throw new Error("Invalid or expired token");
    }
    return {
      token,
      clientId: tokenData.clientId,
      scopes: tokenData.scopes,
      expiresAt: Math.floor(tokenData.expiresAt / 1000),
    };
  }

  isIssuedToken(token) {
    const tokenData = this.tokens.get(token);
    return Boolean(tokenData && tokenData.expiresAt >= Date.now());
  }
}

export function mountOAuth(app, { publicUrl, provider }) {
  const issuerUrl = new URL(publicUrl);
  app.use(
    mcpAuthRouter({
      provider,
      issuerUrl,
      baseUrl: issuerUrl,
      resourceServerUrl: new URL("/mcp", publicUrl),
      scopesSupported: ["mcp:tools"],
      resourceName: "Andro MCP",
    }),
  );
}

/** PKCE helper kept for tests / debugging */
export function s256(verifier) {
  return createHash("sha256").update(verifier).digest("base64url");
}
