/**
 * Andro connector relay.
 *
 * Lets ChatGPT / Claude in the browser reach a workspace that lives on the
 * user's own machine, with no port-forwarding, tunnel binary or config file:
 *
 *   Andro desktop  --outbound WSS-->  relay  <--HTTPS--  ChatGPT / Claude
 *        |                                                     |
 *   localhost:8083 (their index)                    https://<relay>/w/<id>/mcp?k=<key>
 *
 * The desktop app dials out, so NAT and firewalls are a non-issue. The user's
 * only action is copying one URL (or clicking "install" for a local client).
 *
 * Demo-grade: workspace registry is in memory, optionally mirrored to
 * RELAY_STATE_FILE so an App Runner redeploy does not invalidate live URLs.
 */

import { randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { WebSocketServer } from "ws";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";
import { registerAndroTools } from "./tools.js";

const CALL_TIMEOUT_MS = Number(process.env.RELAY_CALL_TIMEOUT_MS || 60_000);
const AGENT_IDLE_MS = Number(process.env.RELAY_AGENT_IDLE_MS || 90_000);
const STATE_FILE = process.env.RELAY_STATE_FILE || "";

const token = (bytes = 24) => randomBytes(bytes).toString("base64url");

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

/* ------------------------------------------------------------------ */
/* Workspace registry                                                  */
/* ------------------------------------------------------------------ */

class WorkspaceRegistry {
  constructor() {
    /** @type {Map<string, any>} */
    this.workspaces = new Map();
    this.#load();
  }

  #load() {
    if (!STATE_FILE) return;
    try {
      const raw = JSON.parse(readFileSync(STATE_FILE, "utf8"));
      for (const w of raw.workspaces || []) {
        this.workspaces.set(w.id, { ...w, socket: null, pending: new Map() });
      }
      console.log(`[relay] restored ${this.workspaces.size} workspace(s)`);
    } catch {
      /* first boot / unreadable — start empty */
    }
  }

  #persist() {
    if (!STATE_FILE) return;
    try {
      const workspaces = [...this.workspaces.values()].map(
        ({ id, agentToken, connectKey, label, service, createdAt }) => ({
          id,
          agentToken,
          connectKey,
          label,
          service,
          createdAt,
        }),
      );
      writeFileSync(STATE_FILE, JSON.stringify({ workspaces }, null, 2));
    } catch (error) {
      console.error("[relay] persist failed", error);
    }
  }

  register({ label, service }) {
    const ws = {
      id: token(9),
      agentToken: token(32),
      connectKey: token(24),
      label: label || "Andro workspace",
      service: service || "local",
      createdAt: new Date().toISOString(),
      socket: null,
      lastSeen: null,
      pending: new Map(),
    };
    this.workspaces.set(ws.id, ws);
    this.#persist();
    return ws;
  }

  get(id) {
    return this.workspaces.get(id);
  }

  /** Rotate the connect key — revokes every connector URL handed out so far. */
  rotate(id) {
    const ws = this.workspaces.get(id);
    if (!ws) return null;
    ws.connectKey = token(24);
    this.#persist();
    return ws;
  }

  remove(id) {
    const ws = this.workspaces.get(id);
    ws?.socket?.close();
    this.workspaces.delete(id);
    this.#persist();
  }

  byAgentToken(agentToken) {
    for (const ws of this.workspaces.values()) {
      if (safeEqual(ws.agentToken, agentToken)) return ws;
    }
    return null;
  }
}

/* ------------------------------------------------------------------ */
/* Relay transport — tool HTTP forwarded to the desktop agent           */
/* ------------------------------------------------------------------ */

function createRelayTransport(ws) {
  return {
    async request(method, path, body) {
      if (!ws.socket || ws.socket.readyState !== 1) {
        throw new Error(
          "Andro desktop is not connected. Open the Andro app on the machine that holds this workspace, then retry.",
        );
      }
      const id = token(8);
      const frame = JSON.stringify({ id, type: "http", method, path, body });

      const reply = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          ws.pending.delete(id);
          reject(new Error(`Timed out after ${CALL_TIMEOUT_MS}ms waiting for Andro desktop`));
        }, CALL_TIMEOUT_MS);

        ws.pending.set(id, {
          resolve: (value) => {
            clearTimeout(timer);
            resolve(value);
          },
          reject: (error) => {
            clearTimeout(timer);
            reject(error);
          },
        });

        ws.socket.send(frame, (error) => {
          if (error) {
            clearTimeout(timer);
            ws.pending.delete(id);
            reject(error);
          }
        });
      });

      if (reply.error) {
        throw new Error(reply.error);
      }
      if (reply.status >= 400) {
        throw new Error(
          `Local Andro API returned ${reply.status}: ${
            typeof reply.body === "string" ? reply.body : JSON.stringify(reply.body)
          }`,
        );
      }
      return reply.body;
    },
  };
}

/* ------------------------------------------------------------------ */
/* Mount                                                               */
/* ------------------------------------------------------------------ */

/**
 * Mount relay routes on an Express app.
 * Returns `{ registry, attach(httpServer) }` — call `attach` after `listen`
 * so the agent WebSocket upgrade is handled.
 */
export function mountRelay(app, { publicUrl }) {
  const registry = new WorkspaceRegistry();
  const base = publicUrl.replace(/\/$/, "");
  const wsBase = base.replace(/^http/, "ws");

  const describe = (ws) => ({
    workspaceId: ws.id,
    label: ws.label,
    service: ws.service,
    connectorUrl: `${base}/w/${ws.id}/mcp?k=${ws.connectKey}`,
    agentUrl: `${wsBase}/agent`,
    connected: ws.socket?.readyState === 1,
    lastSeen: ws.lastSeen,
  });

  /** Desktop app claims a workspace slot. Returns the secrets exactly once. */
  app.post("/pair/register", (req, res) => {
    const ws = registry.register({
      label: req.body?.label,
      service: req.body?.service,
    });
    console.log(`[relay] registered workspace ${ws.id} (${ws.label})`);
    res.json({ ...describe(ws), agentToken: ws.agentToken });
  });

  /** Desktop app polls this to show live status in Settings. */
  app.get("/pair/:workspaceId/status", (req, res) => {
    const ws = registry.get(req.params.workspaceId);
    if (!ws || !safeEqual(ws.agentToken, bearer(req))) {
      return res.status(404).json({ error: "Unknown workspace" });
    }
    res.json(describe(ws));
  });

  /** Rotate the connect key — old connector URLs stop working immediately. */
  app.post("/pair/:workspaceId/rotate", (req, res) => {
    const ws = registry.get(req.params.workspaceId);
    if (!ws || !safeEqual(ws.agentToken, bearer(req))) {
      return res.status(404).json({ error: "Unknown workspace" });
    }
    registry.rotate(ws.id);
    res.json(describe(ws));
  });

  app.delete("/pair/:workspaceId", (req, res) => {
    const ws = registry.get(req.params.workspaceId);
    if (!ws || !safeEqual(ws.agentToken, bearer(req))) {
      return res.status(404).json({ error: "Unknown workspace" });
    }
    registry.remove(ws.id);
    res.status(204).end();
  });

  /* --- MCP endpoint per workspace --- */

  function authorize(req, res) {
    const ws = registry.get(req.params.workspaceId);
    if (!ws) {
      res.status(404).json({
        jsonrpc: "2.0",
        error: { code: -32001, message: "Unknown Andro workspace" },
        id: null,
      });
      return null;
    }
    const key = String(req.query.k || "") || bearer(req);
    if (!key || !safeEqual(ws.connectKey, key)) {
      res.status(401).json({
        jsonrpc: "2.0",
        error: { code: -32001, message: "Invalid or revoked Andro connector key" },
        id: null,
      });
      return null;
    }
    return ws;
  }

  // Stateless: one MCP server per request, so a relay restart never strands a
  // client session and multiple browser clients can share one workspace.
  app.all("/w/:workspaceId/mcp", async (req, res) => {
    const ws = authorize(req, res);
    if (!ws) return;

    if (req.method === "GET" || req.method === "DELETE") {
      // No server-initiated streaming in stateless mode.
      return res.status(405).json({
        jsonrpc: "2.0",
        error: { code: -32000, message: "Method not allowed (stateless relay)" },
        id: null,
      });
    }

    const server = new McpServer(
      { name: `andro-${ws.label}`, version: "1.0.0" },
      { capabilities: { tools: {} } },
    );
    registerAndroTools(server, z, {
      transport: createRelayTransport(ws),
      defaultService: ws.service,
    });

    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => {
      transport.close();
      server.close();
    });

    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      console.error(`[relay] /w/${ws.id}/mcp error`, error);
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: "2.0",
          error: { code: -32603, message: String(error?.message || error) },
          id: null,
        });
      }
    }
  });

  /* --- Agent WebSocket --- */

  function attach(httpServer) {
    const wss = new WebSocketServer({ noServer: true });

    httpServer.on("upgrade", (req, socket, head) => {
      const url = new URL(req.url, "http://relay.invalid");
      if (url.pathname !== "/agent") {
        return; // leave other upgrades alone
      }
      const auth = req.headers.authorization || "";
      const agentToken = auth.startsWith("Bearer ")
        ? auth.slice(7).trim()
        : url.searchParams.get("t") || "";
      const ws = agentToken ? registry.byAgentToken(agentToken) : null;
      if (!ws) {
        socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
        socket.destroy();
        return;
      }
      wss.handleUpgrade(req, socket, head, (socketConn) => {
        bindAgent(ws, socketConn);
      });
    });

    console.log(`[relay] agent WebSocket listening on ${wsBase}/agent`);
    return wss;
  }

  function bindAgent(ws, socket) {
    ws.socket?.close(4000, "replaced by a newer connection");
    ws.socket = socket;
    ws.lastSeen = new Date().toISOString();
    console.log(`[relay] agent connected for workspace ${ws.id}`);

    socket.on("message", (raw) => {
      ws.lastSeen = new Date().toISOString();
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (msg.type === "hello") {
        ws.service = msg.service || ws.service;
        ws.label = msg.label || ws.label;
        return;
      }
      const waiter = ws.pending.get(msg.id);
      if (!waiter) return;
      ws.pending.delete(msg.id);
      waiter.resolve(msg);
    });

    const heartbeat = setInterval(() => {
      if (socket.readyState === 1) {
        socket.ping();
      }
    }, AGENT_IDLE_MS / 3);

    socket.on("pong", () => {
      ws.lastSeen = new Date().toISOString();
    });

    socket.on("close", () => {
      clearInterval(heartbeat);
      if (ws.socket === socket) {
        ws.socket = null;
      }
      for (const [id, waiter] of ws.pending) {
        waiter.reject(new Error("Andro desktop disconnected mid-call"));
        ws.pending.delete(id);
      }
      console.log(`[relay] agent disconnected for workspace ${ws.id}`);
    });

    socket.on("error", (error) => console.error(`[relay] socket error ${ws.id}`, error));
  }

  return { registry, attach };
}

function bearer(req) {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}
