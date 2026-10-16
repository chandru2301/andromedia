#!/usr/bin/env node
import { randomUUID } from 'node:crypto';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { createMcpExpressApp } from '@modelcontextprotocol/sdk/server/express.js';
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import { androBaseUrl, registerAndroTools } from './tools.js';
import { AndroAuthProvider, mountOAuth } from './oauth.js';
import { mountRelay } from './relay.js';
import { mountUiEvents, instrumentMcpServer, emitUiEvent } from './ui-events.js';

const TRANSPORT = (process.env.MCP_TRANSPORT || 'http').toLowerCase();
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const MCP_API_KEY = process.env.MCP_API_KEY?.trim() || '';
const PUBLIC_URL = (process.env.MCP_PUBLIC_URL || process.env.PUBLIC_URL || `http://127.0.0.1:${PORT}`).replace(/\/$/, '');
const RELAY_ENABLED = (process.env.RELAY_ENABLED || 'true').toLowerCase() !== 'false';
const authProvider = new AndroAuthProvider();

function createServer() {
  const server = new McpServer({ name: 'andro', version: '1.0.0' }, { capabilities: { tools: {} } });
  instrumentMcpServer(server);
  registerAndroTools(server, z);
  return server;
}

function requireAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token || !(MCP_API_KEY && token === MCP_API_KEY) && !authProvider.isIssuedToken(token)) {
    res.setHeader('WWW-Authenticate', `Bearer realm="andro", resource_metadata="${PUBLIC_URL}/.well-known/oauth-protected-resource"`);
    return res.status(401).json({ jsonrpc: '2.0', error: { code: -32001, message: token ? 'Unauthorized: invalid token' : 'Unauthorized' }, id: null });
  }
  return next();
}

async function startStdio() {
  const server = createServer();
  await server.connect(new StdioServerTransport());
  console.error(`Andro MCP (stdio) → ${androBaseUrl()}`);
}

async function startHttp() {
  const app = createMcpExpressApp({ host: HOST });
  const streamableSessions = new Map();
  const sseSessions = new Map();
  mountUiEvents(app);

  app.get('/health', (_req, res) => res.json({ status: 'UP', transport: 'http', androBaseUrl: androBaseUrl(), oauth: true, relay: RELAY_ENABLED, events: true, publicUrl: PUBLIC_URL }));
  mountOAuth(app, { publicUrl: PUBLIC_URL, provider: authProvider });
  const relay = RELAY_ENABLED ? mountRelay(app, { publicUrl: PUBLIC_URL }) : null;
  app.use(['/mcp', '/sse', '/messages'], requireAuth);

  app.post('/mcp', async (req, res) => {
    try {
      const sessionId = req.headers['mcp-session-id'];
      let transport = sessionId ? streamableSessions.get(sessionId) : undefined;
      if (!transport && isInitializeRequest(req.body)) {
        const server = createServer();
        transport = new StreamableHTTPServerTransport({ sessionIdGenerator: () => randomUUID(), onsessioninitialized: id => streamableSessions.set(id, transport) });
        transport.onclose = () => { const id = transport.sessionId; if (id) streamableSessions.delete(id); };
        await server.connect(transport);
      } else if (!transport) {
        const server = createServer();
        transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
        await server.connect(transport);
        await transport.handleRequest(req, res, req.body);
        res.on('close', () => { transport.close(); server.close(); });
        return;
      }
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      emitUiEvent({ id: randomUUID(), type: 'server.error', kind: 'command', status: 'error', title: 'MCP request failed', detail: String(error?.message || error) });
      if (!res.headersSent) res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Internal server error' }, id: null });
    }
  });

  app.get('/mcp', async (req, res) => {
    const transport = streamableSessions.get(req.headers['mcp-session-id']);
    if (!transport) return res.status(400).json({ jsonrpc: '2.0', error: { code: -32000, message: 'Invalid or missing mcp-session-id' }, id: null });
    await transport.handleRequest(req, res);
  });
  app.delete('/mcp', async (req, res) => {
    const id = req.headers['mcp-session-id'];
    const transport = streamableSessions.get(id);
    if (transport) { await transport.handleRequest(req, res); streamableSessions.delete(id); return; }
    res.status(400).end();
  });
  app.get('/sse', async (_req, res) => {
    try {
      const server = createServer();
      const transport = new SSEServerTransport('/messages', res);
      sseSessions.set(transport.sessionId, { server, transport });
      transport.onclose = () => sseSessions.delete(transport.sessionId);
      await server.connect(transport);
    } catch (error) {
      emitUiEvent({ id: randomUUID(), type: 'server.error', kind: 'command', status: 'error', title: 'SSE failed', detail: String(error?.message || error) });
      if (!res.headersSent) res.status(500).end();
    }
  });
  app.post('/messages', async (req, res) => {
    const entry = typeof req.query.sessionId === 'string' ? sseSessions.get(req.query.sessionId) : undefined;
    if (!entry) return res.status(400).send('Unknown session');
    await entry.transport.handlePostMessage(req, res, req.body);
  });

  const httpServer = app.listen(PORT, HOST, () => console.log(`Andro MCP HTTP on http://${HOST}:${PORT} → ${androBaseUrl()} public=${PUBLIC_URL}`));
  relay?.attach(httpServer);
}

if (TRANSPORT === 'stdio') await startStdio(); else await startHttp();
