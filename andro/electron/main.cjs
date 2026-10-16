const { app, BrowserWindow, shell, ipcMain, clipboard, Menu, dialog } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { ConnectorAgent } = require('./mcp-agent.cjs');
const connectorStore = require('./connector.cjs');
const localClients = require('./local-clients.cjs');
const updater = require('./updater.cjs');

const isDev = !app.isPackaged;
const devServerUrl = process.env.ANDRO_DEV_SERVER_URL || 'http://localhost:5173';
const PROTOCOL = 'andro';

/** Local core API the desktop app talks to (Local Pro mode). */
const LOCAL_API_URL = process.env.ANDRO_LOCAL_API_URL || 'http://localhost:8083';

/**
 * Local agent that owns the sandboxed file tools (Read/Grep) and the
 * Andromedia context tools. Separate process, separate trust boundary: it holds
 * the workspace jail and the approval queue.
 */
const AGENT_URL = (process.env.ANDRO_AGENT_URL || 'http://127.0.0.1:8787').replace(/\/$/, '');

/** Where the agent keeps its bearer token. */
const AGENT_TOKEN_PATH = path.join(
  app.getPath('home'),
  '.config',
  'browser-agent-mcp',
  'token',
);

/**
 * Read the agent token.
 *
 * Deliberately confined to the main process: the renderer is sandboxed and must
 * never hold a credential that can read the filesystem. Requests are proxied
 * over IPC instead, so a bug in page JavaScript cannot exfiltrate the token.
 */
function readAgentToken() {
  try {
    return fs.readFileSync(AGENT_TOKEN_PATH, 'utf8').trim();
  } catch {
    return null;
  }
}

/** Call the local agent, returning { ok, status, body } instead of throwing. */
async function callAgent(pathname, init = {}) {
  const token = readAgentToken();
  if (!token) {
    return {
      ok: false,
      status: 0,
      body: {
        error:
          'Andro Agent is not set up. Run: andro-agent init && andro-agent start',
      },
    };
  }
  try {
    const response = await fetch(`${AGENT_URL}${pathname}`, {
      method: init.method || 'GET',
      body: init.body,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      signal: AbortSignal.timeout(init.timeoutMs ?? 30_000),
    });
    const body = await response.json().catch(() => ({}));
    return { ok: response.ok, status: response.status, body };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      body: {
        error:
          `Could not reach Andro Agent at ${AGENT_URL} — is it running? ` +
          `(${error instanceof Error ? error.message : String(error)})`,
      },
    };
  }
}

/** @type {BrowserWindow | null} */
let mainWindow = null;
/**
 * Deep-link URL waiting to be claimed by the renderer.
 *
 * Held until the renderer explicitly consumes it (andro:consume-auth-callback)
 * rather than cleared on send: webContents.send drops the message when no
 * listener is mounted yet, which silently lost sign-in tickets whenever the
 * callback arrived while the user was outside the auth screen.
 */
let pendingAuthUrl = null;

/** Outbound relay agent that exposes this machine's workspace to ChatGPT / Claude. */
const connectorAgent = new ConnectorAgent();

function isAuthCallbackUrl(url) {
  return typeof url === 'string' && url.startsWith(`${PROTOCOL}://`);
}

function extractAuthUrlFromArgv(argv) {
  return (argv || []).find((arg) => isAuthCallbackUrl(arg)) ?? null;
}

function focusMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return;
  }
  if (mainWindow.isMinimized()) {
    mainWindow.restore();
  }
  mainWindow.show();
  mainWindow.focus();
}

function deliverAuthCallback(url) {
  if (!isAuthCallbackUrl(url)) {
    return;
  }
  // Keep it queued even after sending; the renderer clears it once redeemed.
  pendingAuthUrl = url;
  if (!mainWindow || mainWindow.isDestroyed()) {
    return;
  }
  focusMainWindow();
  mainWindow.webContents.send('andro:auth-callback', url);
}

function broadcastConnectorStatus(status) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('andro:connector-status', status);
  }
}

/* ---- Window + zoom state (developers resize constantly; remember it) ---- */

function stateFile() {
  return path.join(app.getPath('userData'), 'window-state.json');
}

function readState() {
  try {
    return JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
  } catch {
    return {};
  }
}

function writeState(patch) {
  try {
    const next = { ...readState(), ...patch };
    fs.writeFileSync(stateFile(), JSON.stringify(next));
  } catch {
    // Non-fatal: a state file we cannot write just means defaults next launch.
  }
}

function readWindowState() {
  const saved = readState().window;
  if (!saved || typeof saved.width !== 'number' || typeof saved.height !== 'number') {
    return null;
  }
  return saved;
}

function writeWindowState(win) {
  if (!win || win.isDestroyed()) {
    return;
  }
  const maximized = win.isMaximized() || win.isFullScreen();
  // Persist the *restored* bounds so unmaximizing after a restart looks right.
  const bounds = maximized ? win.getNormalBounds() : win.getBounds();
  writeState({ window: { ...bounds, maximized } });
}

function readZoomFactor() {
  const saved = readState().zoomFactor;
  return typeof saved === 'number' && saved >= 0.5 && saved <= 3 ? saved : 1;
}

/** Apply a zoom step and remember it, so the choice survives a restart. */
function applyZoom(direction) {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return 1;
  }
  const contents = mainWindow.webContents;
  let next = contents.getZoomFactor();
  if (direction === 'in') {
    next = Math.min(3, next + 0.1);
  } else if (direction === 'out') {
    next = Math.max(0.5, next - 0.1);
  } else {
    next = 1;
  }
  // Round to avoid 1.0000000000000002-style drift across repeated steps.
  next = Math.round(next * 100) / 100;
  contents.setZoomFactor(next);
  writeState({ zoomFactor: next });
  return next;
}

/* ---- Developer-oriented application menu ---- */

function buildMenu() {
  const isMac = process.platform === 'darwin';
  const zoomItems = [
    {
      label: 'Actual Size',
      accelerator: 'CommandOrControl+0',
      click: () => applyZoom('reset'),
    },
    { type: 'separator' },
    { label: 'Zoom In', accelerator: 'CommandOrControl+Plus', click: () => applyZoom('in') },
    {
      label: 'Zoom In',
      accelerator: 'CommandOrControl+=',
      visible: false,
      click: () => applyZoom('in'),
    },
    { label: 'Zoom Out', accelerator: 'CommandOrControl+-', click: () => applyZoom('out') },
  ];

  const viewSubmenu = [
    { role: 'reload', accelerator: 'CommandOrControl+R' },
    { role: 'forceReload', accelerator: 'CommandOrControl+Shift+R' },
    { type: 'separator' },
    ...zoomItems,
    { type: 'separator' },
    { role: 'togglefullscreen' },
    {
      label: 'Toggle Developer Tools',
      accelerator: isMac ? 'Alt+Command+I' : 'Control+Shift+I',
      click: () => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.toggleDevTools();
        }
      },
    },
  ];

  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    {
      label: 'File',
      submenu: [
        {
          label: 'Open Settings',
          accelerator: 'CommandOrControl+,',
          click: () => {
            if (mainWindow && !mainWindow.isDestroyed()) {
              mainWindow.webContents.send('andro:open-settings');
            }
          },
        },
        { type: 'separator' },
        {
          label: 'Copy Local API URL',
          click: () => clipboard.writeText(LOCAL_API_URL),
        },
        {
          label: 'Copy MCP Config',
          click: () => {
            if (mainWindow && !mainWindow.isDestroyed()) {
              mainWindow.webContents.send('andro:copy-mcp-config');
            }
          },
        },
        { type: 'separator' },
        {
          label: 'Open Core API Health',
          click: () => void shell.openExternal(`${LOCAL_API_URL}/api/v1/health`),
        },
        { type: 'separator' },
        isMac ? { role: 'close' } : { role: 'quit' },
      ],
    },
    { label: 'Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    { label: 'View', submenu: viewSubmenu },
    {
      label: 'Window',
      submenu: [{ role: 'minimize' }, { role: 'zoom' }, ...(isMac ? [{ type: 'separator' }, { role: 'front' }] : [])],
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'Diagnostics',
          click: () => showDiagnostics(),
        },
        { type: 'separator' },
        {
          label: `Version ${app.getVersion()}`,
          enabled: false,
        },
      ],
    },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

/** A quick, copyable environment report — the first thing to ask for in a bug thread. */
function diagnosticsReport() {
  return [
    `andro ${app.getVersion()}`,
    `electron ${process.versions.electron} · chrome ${process.versions.chrome} · node ${process.versions.node}`,
    `platform ${process.platform} ${process.arch}`,
    `packaged ${app.isPackaged}`,
    `local API ${LOCAL_API_URL}`,
    `dev server ${isDev ? devServerUrl : '(bundled)'}`,
    `userData ${app.getPath('userData')}`,
  ].join('\n');
}

function showDiagnostics() {
  const detail = diagnosticsReport();
  void dialog
    .showMessageBox({
      type: 'info',
      title: 'Diagnostics',
      message: 'Environment',
      detail,
      buttons: ['Copy', 'Close'],
      defaultId: 0,
      cancelId: 1,
    })
    .then(({ response }) => {
      if (response === 0) {
        clipboard.writeText(detail);
      }
    });
}

connectorAgent.on('status', broadcastConnectorStatus);

/** Bring the agent up from whatever was persisted on a previous run. */
async function resumeConnector() {
  const saved = connectorStore.readStore();
  if (!saved?.workspaceId || saved.enabled === false) {
    return;
  }
  connectorAgent.start(saved);
}

function connectorSnapshot() {
  const saved = connectorStore.readStore();
  return {
    ...connectorAgent.status(),
    paired: Boolean(saved?.workspaceId),
    connectorUrl: saved?.connectorUrl ?? null,
    relayUrl: saved?.relayUrl ?? connectorStore.DEFAULT_RELAY,
    localApiUrl: saved?.localApiUrl ?? connectorStore.DEFAULT_LOCAL_API,
    label: saved?.label ?? null,
    clients: localClients.detectClients(),
  };
}

function createWindow() {
  const savedBounds = readWindowState();
  mainWindow = new BrowserWindow({
    width: savedBounds?.width ?? 1360,
    height: savedBounds?.height ?? 860,
    x: savedBounds?.x,
    y: savedBounds?.y,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#0a0a0a',
    title: 'andro',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    autoHideMenuBar: process.platform !== 'darwin',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // Developers live in devtools; keep the last-used zoom level across restarts.
      zoomFactor: readZoomFactor(),
    },
  });

  if (savedBounds?.maximized) {
    mainWindow.maximize();
  }

  // Open external links (e.g. docs, OAuth) in the system browser instead of a new Electron window.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });

  // Block in-app navigation away from the app shell (e.g. a stray anchor, or a
  // redirect from the local API) so the window can never end up showing a raw
  // JSON/HTML page with no way back.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const current = mainWindow?.webContents.getURL() ?? '';
    const sameOrigin = (() => {
      try {
        return new URL(url).origin === new URL(current).origin;
      } catch {
        return false;
      }
    })();
    if (!sameOrigin) {
      event.preventDefault();
      if (/^https?:\/\//i.test(url)) {
        void shell.openExternal(url);
      }
    }
  });

  const persistState = () => writeWindowState(mainWindow);
  mainWindow.on('resize', persistState);
  mainWindow.on('move', persistState);
  mainWindow.on('maximize', persistState);
  mainWindow.on('unmaximize', persistState);
  mainWindow.on('enter-full-screen', persistState);
  mainWindow.on('leave-full-screen', persistState);

  mainWindow.webContents.on('did-finish-load', () => {
    if (pendingAuthUrl) {
      deliverAuthCallback(pendingAuthUrl);
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  if (isDev) {
    mainWindow.loadURL(devServerUrl);
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const url = extractAuthUrlFromArgv(argv);
    if (url) {
      deliverAuthCallback(url);
    } else {
      focusMainWindow();
    }
  });

  // macOS deep links
  app.on('open-url', (event, url) => {
    event.preventDefault();
    deliverAuthCallback(url);
  });

  // Register protocol as early as possible (packaged builds also use electron-builder protocols).
  let protocolRegistered = false;
  if (process.defaultApp) {
    if (process.argv.length >= 2) {
      protocolRegistered = app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [
        path.resolve(process.argv[1]),
      ]);
    }
  } else {
    protocolRegistered = app.setAsDefaultProtocolClient(PROTOCOL);
  }

  if (!protocolRegistered) {
    // Electron cannot self-register the scheme here. On Linux that is expected
    // for an unpackaged run (it needs a .desktop file declaring
    // x-scheme-handler/andro), and the scheme may already be registered by
    // scripts/register-protocol-linux.sh — so report it, don't assert failure.
    console.warn(
      `[andro] Electron did not register the ${PROTOCOL}:// handler itself.` +
        (process.platform === 'linux'
          ? ' If browser sign-in does not return to the app, run:' +
            ' ./scripts/register-protocol-linux.sh (check with:' +
            ' xdg-mime query default x-scheme-handler/andro)'
          : ' Browser sign-in cannot return until it is registered.'),
    );
  }

  /* ---- Browser connector (ChatGPT / Claude) ---- */

  ipcMain.handle('andro:connector-status', async () => connectorSnapshot());

  ipcMain.handle('andro:connector-enable', async (_event, options = {}) => {
    const saved = await connectorStore.ensurePaired(options);
    connectorStore.writeStore({ ...saved, enabled: true });
    connectorAgent.start({ ...saved, enabled: true });
    return connectorSnapshot();
  });

  ipcMain.handle('andro:connector-disable', async () => {
    const saved = connectorStore.readStore();
    if (saved) {
      connectorStore.writeStore({ ...saved, enabled: false });
    }
    connectorAgent.stop();
    return connectorSnapshot();
  });

  ipcMain.handle('andro:connector-rotate', async () => {
    const saved = await connectorStore.rotateKey();
    connectorAgent.start({ ...saved, enabled: true });
    return connectorSnapshot();
  });

  ipcMain.handle('andro:connector-unpair', async () => {
    connectorAgent.stop();
    await connectorStore.unpair();
    return connectorSnapshot();
  });

  ipcMain.handle('andro:connector-copy-url', async () => {
    const saved = connectorStore.readStore();
    if (!saved?.connectorUrl) {
      throw new Error('No connector URL yet — enable the connector first');
    }
    clipboard.writeText(saved.connectorUrl);
    return true;
  });

  ipcMain.handle('andro:connector-install-client', async (_event, clientId) => {
    const saved = connectorStore.readStore();
    if (!saved?.connectorUrl) {
      throw new Error('No connector URL yet — enable the connector first');
    }
    localClients.installClient(clientId, saved.connectorUrl);
    return connectorSnapshot();
  });

  ipcMain.handle('andro:connector-remove-client', async (_event, clientId) => {
    localClients.removeClient(clientId);
    return connectorSnapshot();
  });

  /**
   * Claim a deep-link URL that arrived before a listener was mounted.
   * Returns null when there is nothing pending.
   */
  ipcMain.handle('andro:consume-auth-callback', async () => {
    const url = pendingAuthUrl;
    pendingAuthUrl = null;
    return url;
  });

  ipcMain.handle('andro:open-external', async (_event, url) => {
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
      throw new Error('Only http(s) URLs can be opened externally');
    }
    await shell.openExternal(url);
    return true;
  });

  /* ---- Developer conveniences exposed to the renderer ---- */

  ipcMain.handle('andro:app-info', async () => ({
    version: app.getVersion(),
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
    platform: process.platform,
    arch: process.arch,
    packaged: app.isPackaged,
    localApiUrl: LOCAL_API_URL,
    devServerUrl: isDev ? devServerUrl : null,
    userData: app.getPath('userData'),
    zoomFactor: readZoomFactor(),
  }));

  ipcMain.handle('andro:zoom', async (_event, direction) => applyZoom(direction));

  ipcMain.handle('andro:toggle-devtools', async () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.toggleDevTools();
      return mainWindow.webContents.isDevToolsOpened();
    }
    return false;
  });

  ipcMain.handle('andro:relaunch', async () => {
    app.relaunch();
    app.exit(0);
  });

  ipcMain.handle('andro:open-logs', async () => {
    const dir = path.join(app.getPath('home'), '.andromedia', 'logs');
    const result = await shell.openPath(dir);
    return result === '' ? dir : null;
  });

  /* ---- Read-only file tools, proxied to the local agent ---- */

  /**
   * Only the read-only operations are proxied. Write/Edit/Bash stay reachable
   * solely through MCP, where they are approval-gated, so exposing this to the
   * renderer does not widen what can modify the workspace.
   */
  ipcMain.handle('andro:files-workspace', async () => callAgent('/files/workspace'));

  ipcMain.handle('andro:files-grep', async (_event, input) =>
    callAgent('/files/grep', {
      method: 'POST',
      body: JSON.stringify(input ?? {}),
      timeoutMs: 60_000,
    }),
  );

  ipcMain.handle('andro:files-read', async (_event, input) =>
    callAgent('/files/read', {
      method: 'POST',
      body: JSON.stringify(input ?? {}),
      timeoutMs: 30_000,
    }),
  );

  app.whenReady().then(() => {
    buildMenu();
    createWindow();
    void resumeConnector();
    updater.init();

    const coldStartUrl = extractAuthUrlFromArgv(process.argv);
    if (coldStartUrl) {
      deliverAuthCallback(coldStartUrl);
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      }
    });
  });

  app.on('before-quit', () => {
    connectorAgent.stop();
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit();
    }
  });
}
