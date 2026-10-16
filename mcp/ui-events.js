const clients = new Set();

export function mountUiEvents(app) {
  app.get('/events', (_req, res) => {
    res.status(200);
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders?.();
    clients.add(res);
    res.write(`data: ${JSON.stringify({ type: 'bridge.connected' })}\n\n`);
    const heartbeat = setInterval(() => { try { res.write(': heartbeat\n\n'); } catch {} }, 15000);
    res.on('close', () => { clearInterval(heartbeat); clients.delete(res); });
  });
}

export function emitUiEvent(event) {
  const payload = `data: ${JSON.stringify({ ...event, timestamp: new Date().toISOString() })}\n\n`;
  for (const res of clients) { try { res.write(payload); } catch { clients.delete(res); } }
}

export function instrumentMcpServer(server) {
  const original = server.registerTool.bind(server);
  server.registerTool = (name, config, handler) => {
    const wrapped = async (args) => {
      const id = crypto.randomUUID();
      const started = Date.now();
      const kind = name.includes('search') || name === 'search' ? 'search' : 'command';
      emitUiEvent({ id, type: 'tool.started', kind, status: 'running', title: name, detail: 'MCP tool execution' });
      try {
        const result = await handler(args);
        emitUiEvent({ id, type: 'tool.completed', kind, status: 'complete', title: name, detail: `completed in ${Date.now() - started}ms` });
        return result;
      } catch (error) {
        emitUiEvent({ id, type: 'tool.failed', kind, status: 'error', title: name, detail: String(error?.message || error) });
        throw error;
      }
    };
    return original(name, config, wrapped);
  };
}
