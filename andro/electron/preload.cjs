const { contextBridge, ipcRenderer } = require('electron');

/**
 * Desktop bridge for Cursor-style browser auth:
 * open system browser for Sign in / Sign up, then receive andro:// callbacks.
 */
contextBridge.exposeInMainWorld('androDesktop', {
  isDesktop: true,
  openExternal: (url) => ipcRenderer.invoke('andro:open-external', url),
  /**
   * Browser connector: pair this machine with the relay so ChatGPT / Claude
   * can reach the local workspace, and one-click install for local clients.
   */
  connector: {
    status: () => ipcRenderer.invoke('andro:connector-status'),
    enable: (options) => ipcRenderer.invoke('andro:connector-enable', options ?? {}),
    disable: () => ipcRenderer.invoke('andro:connector-disable'),
    rotate: () => ipcRenderer.invoke('andro:connector-rotate'),
    unpair: () => ipcRenderer.invoke('andro:connector-unpair'),
    copyUrl: () => ipcRenderer.invoke('andro:connector-copy-url'),
    installClient: (clientId) => ipcRenderer.invoke('andro:connector-install-client', clientId),
    removeClient: (clientId) => ipcRenderer.invoke('andro:connector-remove-client', clientId),
    onStatus: (handler) => {
      if (typeof handler !== 'function') {
        return () => undefined;
      }
      const listener = (_event, status) => {
        handler(status);
      };
      ipcRenderer.on('andro:connector-status', listener);
      return () => {
        ipcRenderer.removeListener('andro:connector-status', listener);
      };
    },
  },
  /** Claim a deep link that arrived before this listener was mounted. */
  consumeAuthCallback: () => ipcRenderer.invoke('andro:consume-auth-callback'),
  onAuthCallback: (handler) => {
    if (typeof handler !== 'function') {
      return () => undefined;
    }
    const listener = (_event, url) => {
      handler(url);
    };
    ipcRenderer.on('andro:auth-callback', listener);
    return () => {
      ipcRenderer.removeListener('andro:auth-callback', listener);
    };
  },
  /**
   * Read-only access to the local working tree, proxied to andro-agent.
   *
   * The agent's bearer token stays in the main process; the renderer receives
   * results only. Write/Edit/Bash are deliberately not exposed here — they
   * remain MCP-only so they stay behind approval gating.
   */
  files: {
    workspace: () => ipcRenderer.invoke('andro:files-workspace'),
    grep: (input) => ipcRenderer.invoke('andro:files-grep', input ?? {}),
    read: (input) => ipcRenderer.invoke('andro:files-read', input ?? {}),
  },
  /**
   * Developer conveniences. Everything here is local-only and read-only with
   * respect to user data; the renderer uses it to show build/runtime facts and
   * to wire menu items to in-app actions.
   */
  dev: {
    appInfo: () => ipcRenderer.invoke('andro:app-info'),
    zoom: (direction) => ipcRenderer.invoke('andro:zoom', direction),
    toggleDevTools: () => ipcRenderer.invoke('andro:toggle-devtools'),
    relaunch: () => ipcRenderer.invoke('andro:relaunch'),
    openLogs: () => ipcRenderer.invoke('andro:open-logs'),
    /** Fired by the app menu (File → Open Settings / Copy MCP Config). */
    onMenu: (channel, handler) => {
      if (typeof handler !== 'function') {
        return () => undefined;
      }
      const allowed = ['andro:open-settings', 'andro:copy-mcp-config'];
      if (!allowed.includes(channel)) {
        return () => undefined;
      }
      const listener = () => handler();
      ipcRenderer.on(channel, listener);
      return () => {
        ipcRenderer.removeListener(channel, listener);
      };
    },
  },
});
