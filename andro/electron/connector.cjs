/**
 * Connector pairing + persistence for the Andro desktop app.
 *
 * One-time flow, entirely automatic from the user's point of view:
 *   1. app POSTs /pair/register on the relay
 *   2. relay returns { workspaceId, agentToken, connectorUrl }
 *   3. we persist it and keep the agent socket up from then on
 *
 * The agent token is the machine's identity and never leaves userData.
 */

const { app } = require('electron');
const { readFileSync, writeFileSync, rmSync } = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const DEFAULT_RELAY = process.env.ANDRO_RELAY_URL || 'https://wykugmbu2n.us-east-1.awsapprunner.com';
const DEFAULT_LOCAL_API = process.env.ANDRO_LOCAL_API_URL || 'http://localhost:8083';

function storePath() {
  return path.join(app.getPath('userData'), 'connector.json');
}

function readStore() {
  try {
    return JSON.parse(readFileSync(storePath(), 'utf8'));
  } catch {
    return null;
  }
}

function writeStore(value) {
  writeFileSync(storePath(), JSON.stringify(value, null, 2), { mode: 0o600 });
  return value;
}

function clearStore() {
  try {
    rmSync(storePath());
  } catch {
    /* nothing stored */
  }
}

async function relayJson(url, options = {}) {
  const res = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  if (!res.ok) {
    throw new Error(`Relay returned ${res.status}: ${await res.text()}`);
  }
  return res.status === 204 ? null : res.json();
}

/**
 * Return the saved pairing, registering a new workspace on first use.
 * @param opts {{relayUrl?:string, localApiUrl?:string, service?:string}}
 */
async function ensurePaired(opts = {}) {
  const existing = readStore();
  const relayUrl = (opts.relayUrl || existing?.relayUrl || DEFAULT_RELAY).replace(/\/$/, '');
  const localApiUrl = opts.localApiUrl || existing?.localApiUrl || DEFAULT_LOCAL_API;
  const service = opts.service || existing?.service || 'local';

  if (existing?.workspaceId && existing.relayUrl === relayUrl) {
    return writeStore({ ...existing, localApiUrl, service });
  }

  const label = `${os.userInfo().username}@${os.hostname()}`;
  const paired = await relayJson(`${relayUrl}/pair/register`, {
    method: 'POST',
    body: JSON.stringify({ label, service }),
  });

  return writeStore({
    relayUrl,
    localApiUrl,
    service,
    label,
    workspaceId: paired.workspaceId,
    agentToken: paired.agentToken,
    connectorUrl: paired.connectorUrl,
    pairedAt: new Date().toISOString(),
  });
}

/** Rotate the connect key — every connector URL handed out so far stops working. */
async function rotateKey() {
  const saved = readStore();
  if (!saved?.workspaceId) {
    throw new Error('No connector is paired yet');
  }
  const next = await relayJson(`${saved.relayUrl}/pair/${saved.workspaceId}/rotate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${saved.agentToken}` },
  });
  return writeStore({ ...saved, connectorUrl: next.connectorUrl });
}

/** Unpair: revoke on the relay (best effort) and forget locally. */
async function unpair() {
  const saved = readStore();
  if (saved?.workspaceId) {
    try {
      await relayJson(`${saved.relayUrl}/pair/${saved.workspaceId}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${saved.agentToken}` },
      });
    } catch {
      /* relay may be down or already forgot us — local clear still matters */
    }
  }
  clearStore();
}

module.exports = {
  DEFAULT_RELAY,
  DEFAULT_LOCAL_API,
  readStore,
  writeStore,
  clearStore,
  ensurePaired,
  rotateKey,
  unpair,
};
