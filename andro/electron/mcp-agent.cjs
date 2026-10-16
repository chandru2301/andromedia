/**
 * Andro connector agent (Electron main process).
 *
 * Holds one outbound WebSocket to the Andro relay. The relay forwards MCP tool
 * traffic from ChatGPT / Claude down this socket; we execute it against the
 * user's own local Andromedia API and send the JSON back.
 *
 * Nothing listens on a public port, so the user never touches firewall,
 * router or tunnel settings.
 */

const { EventEmitter } = require('node:events');

/** Only Andromedia read paths may be driven remotely — this is not a generic proxy. */
const ALLOWED_PATH = /^\/api\/v1\/(investigate|search|index\/services\/[^/]+\/overview)(\?|$)/;
const ALLOWED_METHODS = new Set(['GET', 'POST']);

const BACKOFF_MS = [1000, 2000, 5000, 10000, 20000, 30000];
const LOCAL_TIMEOUT_MS = 55_000;

class ConnectorAgent extends EventEmitter {
  constructor() {
    super();
    this.socket = null;
    this.config = null;
    this.attempt = 0;
    this.retryTimer = null;
    this.stopped = true;
    this.state = 'disabled';
    this.lastError = null;
  }

  status() {
    return {
      state: this.state,
      lastError: this.lastError,
      workspaceId: this.config?.workspaceId ?? null,
      connectorUrl: this.config?.connectorUrl ?? null,
      relayUrl: this.config?.relayUrl ?? null,
      localApiUrl: this.config?.localApiUrl ?? null,
      service: this.config?.service ?? null,
    };
  }

  #setState(state, error) {
    this.state = state;
    this.lastError = error ? String(error.message || error) : null;
    this.emit('status', this.status());
  }

  /** @param config {{relayUrl,workspaceId,agentToken,connectorUrl,localApiUrl,service,label}} */
  start(config) {
    this.stop();
    this.config = config;
    this.stopped = false;
    this.attempt = 0;
    this.#connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    this.retryTimer = null;
    if (this.socket) {
      const socket = this.socket;
      this.socket = null;
      try {
        socket.close();
      } catch {
        /* already closing */
      }
    }
    this.#setState('disabled', null);
  }

  #scheduleRetry() {
    if (this.stopped) return;
    const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt += 1;
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.#connect(), delay);
  }

  #connect() {
    if (this.stopped || !this.config) return;
    if (typeof WebSocket !== 'function') {
      this.#setState('error', new Error('This Electron build has no WebSocket support'));
      return;
    }

    const { relayUrl, agentToken } = this.config;
    const url = `${relayUrl.replace(/\/$/, '').replace(/^http/, 'ws')}/agent?t=${encodeURIComponent(agentToken)}`;

    this.#setState('connecting', null);

    let socket;
    try {
      socket = new WebSocket(url);
    } catch (error) {
      this.#setState('error', error);
      this.#scheduleRetry();
      return;
    }
    this.socket = socket;

    socket.addEventListener('open', () => {
      this.attempt = 0;
      this.#setState('connected', null);
      this.#send({
        type: 'hello',
        service: this.config.service,
        label: this.config.label,
        version: 1,
      });
    });

    socket.addEventListener('message', (event) => {
      void this.#handleFrame(event.data);
    });

    socket.addEventListener('close', (event) => {
      if (this.socket === socket) {
        this.socket = null;
      }
      if (this.stopped) return;
      this.#setState(
        'reconnecting',
        event?.code === 1008 || event?.code === 4001
          ? new Error('Relay rejected this workspace — re-pair from Settings')
          : null,
      );
      this.#scheduleRetry();
    });

    socket.addEventListener('error', () => {
      // 'close' always follows; surface the reason there.
    });
  }

  #send(payload) {
    if (this.socket?.readyState === 1) {
      this.socket.send(JSON.stringify(payload));
    }
  }

  async #handleFrame(raw) {
    let msg;
    try {
      msg = JSON.parse(typeof raw === 'string' ? raw : raw.toString());
    } catch {
      return;
    }
    if (msg.type !== 'http' || !msg.id) return;

    const method = String(msg.method || 'GET').toUpperCase();
    const path = String(msg.path || '');

    if (!ALLOWED_METHODS.has(method) || !ALLOWED_PATH.test(path)) {
      this.#send({ id: msg.id, error: `Path not permitted by the Andro agent: ${method} ${path}` });
      return;
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), LOCAL_TIMEOUT_MS);
    try {
      const res = await fetch(`${this.config.localApiUrl.replace(/\/$/, '')}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        ...(msg.body === undefined || method === 'GET'
          ? {}
          : { body: JSON.stringify(msg.body) }),
        signal: controller.signal,
      });
      const raw = await res.text();
      let body;
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        body = raw;
      }
      this.#send({ id: msg.id, status: res.status, body });
    } catch (error) {
      const reason =
        error?.name === 'AbortError'
          ? 'Local Andro API timed out'
          : `Local Andro API unreachable at ${this.config.localApiUrl} (${error?.message || error})`;
      this.#send({ id: msg.id, error: reason });
    } finally {
      clearTimeout(timer);
    }
  }
}

module.exports = { ConnectorAgent };
