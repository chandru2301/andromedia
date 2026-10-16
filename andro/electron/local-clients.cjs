/**
 * One-click connector install for local MCP clients.
 *
 * Every client points at the same relay connector URL, so no Node runtime,
 * server path or API key ever has to be pasted by the user.
 *
 * Claude Desktop still only speaks stdio in its config file, so it gets the
 * `mcp-remote` bridge; Cursor and Claude Code take the remote URL directly.
 */

const { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const SERVER_KEY = 'andro';

function claudeDesktopConfigPath() {
  const home = os.homedir();
  if (process.platform === 'darwin') {
    return path.join(home, 'Library', 'Application Support', 'Claude', 'claude_desktop_config.json');
  }
  if (process.platform === 'win32') {
    return path.join(process.env.APPDATA || path.join(home, 'AppData', 'Roaming'), 'Claude', 'claude_desktop_config.json');
  }
  return path.join(home, '.config', 'Claude', 'claude_desktop_config.json');
}

const CLIENTS = {
  cursor: {
    label: 'Cursor',
    file: () => path.join(os.homedir(), '.cursor', 'mcp.json'),
    entry: (url) => ({ url }),
  },
  'claude-code': {
    label: 'Claude Code',
    file: () => path.join(os.homedir(), '.claude.json'),
    entry: (url) => ({ type: 'http', url }),
  },
  'claude-desktop': {
    label: 'Claude Desktop',
    file: claudeDesktopConfigPath,
    // Claude Desktop config is stdio-only; mcp-remote bridges to the relay URL.
    entry: (url) => ({ command: 'npx', args: ['-y', 'mcp-remote', url] }),
  },
};

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return {};
  }
}

/** Merge the Andro server into one client config, preserving everything else. */
function installClient(clientId, connectorUrl) {
  const client = CLIENTS[clientId];
  if (!client) {
    throw new Error(`Unknown MCP client: ${clientId}`);
  }
  const file = client.file();
  mkdirSync(path.dirname(file), { recursive: true });

  const config = readJson(file);
  if (existsSync(file)) {
    copyFileSync(file, `${file}.andro-backup`);
  }

  config.mcpServers = { ...(config.mcpServers || {}), [SERVER_KEY]: client.entry(connectorUrl) };
  writeFileSync(file, `${JSON.stringify(config, null, 2)}\n`);

  return { clientId, label: client.label, file, installed: true };
}

function removeClient(clientId) {
  const client = CLIENTS[clientId];
  if (!client) {
    throw new Error(`Unknown MCP client: ${clientId}`);
  }
  const file = client.file();
  if (!existsSync(file)) {
    return { clientId, label: client.label, file, installed: false };
  }
  const config = readJson(file);
  if (config.mcpServers) {
    delete config.mcpServers[SERVER_KEY];
    writeFileSync(file, `${JSON.stringify(config, null, 2)}\n`);
  }
  return { clientId, label: client.label, file, installed: false };
}

/** What the Settings panel renders: which clients exist and which already have Andro. */
function detectClients() {
  return Object.entries(CLIENTS).map(([clientId, client]) => {
    const file = client.file();
    const config = existsSync(file) ? readJson(file) : null;
    return {
      clientId,
      label: client.label,
      file,
      present: existsSync(file),
      installed: Boolean(config?.mcpServers?.[SERVER_KEY]),
    };
  });
}

module.exports = { installClient, removeClient, detectClients, CLIENTS };
