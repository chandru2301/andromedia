/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ANDROMEDIA_API_URL?: string;
  readonly VITE_LOCAL_ANDROMEDIA_API_URL?: string;
  readonly VITE_CLERK_PUBLISHABLE_KEY?: string;
  readonly VITE_AUTH_WEB_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

type ConnectorState =
  | 'disabled'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'error';

interface ConnectorClient {
  clientId: 'cursor' | 'claude-code' | 'claude-desktop';
  label: string;
  file: string;
  present: boolean;
  installed: boolean;
}

interface ConnectorStatus {
  state: ConnectorState;
  lastError: string | null;
  paired: boolean;
  workspaceId: string | null;
  connectorUrl: string | null;
  relayUrl: string | null;
  localApiUrl: string | null;
  service: string | null;
  label: string | null;
  clients: ConnectorClient[];
}

interface ConnectorBridge {
  status: () => Promise<ConnectorStatus>;
  enable: (options?: {
    relayUrl?: string;
    localApiUrl?: string;
    service?: string;
  }) => Promise<ConnectorStatus>;
  disable: () => Promise<ConnectorStatus>;
  rotate: () => Promise<ConnectorStatus>;
  unpair: () => Promise<ConnectorStatus>;
  copyUrl: () => Promise<boolean>;
  installClient: (clientId: ConnectorClient['clientId']) => Promise<ConnectorStatus>;
  removeClient: (clientId: ConnectorClient['clientId']) => Promise<ConnectorStatus>;
  onStatus: (handler: (status: ConnectorStatus) => void) => () => void;
}

interface AndroAppInfo {
  version: string;
  electron: string;
  chrome: string;
  node: string;
  platform: string;
  arch: string;
  packaged: boolean;
  localApiUrl: string;
  devServerUrl: string | null;
  userData: string;
  zoomFactor: number;
}

type AndroMenuChannel = 'andro:open-settings' | 'andro:copy-mcp-config';

interface AndroGrepMatch {
  path: string;
  line: number;
  text: string;
}

interface AndroGrepResponse {
  matches?: AndroGrepMatch[];
  truncated?: boolean;
  engine?: 'rg' | 'node';
  error?: string;
}

interface AndroReadResponse {
  path?: string;
  content?: string;
  startLine?: number;
  endLine?: number;
  totalLines?: number;
  truncated?: boolean;
  error?: string;
}

/**
 * Read-only working-tree access, proxied to andro-agent by the main process so
 * the agent token never reaches the renderer. Absent in the browser build.
 */
interface AndroFilesBridge {
  workspace: () => Promise<{ ok: boolean; body: { workspaceRoot?: string; error?: string } }>;
  grep: (input: {
    pattern: string;
    path?: string;
    glob?: string;
    case_insensitive?: boolean;
    max_results?: number;
  }) => Promise<{ ok: boolean; body: AndroGrepResponse }>;
  read: (input: {
    path: string;
    offset?: number;
    limit?: number;
  }) => Promise<{ ok: boolean; body: AndroReadResponse }>;
}

/** Local-only developer conveniences; absent in the browser build. */
interface AndroDevBridge {
  appInfo: () => Promise<AndroAppInfo>;
  zoom: (direction: 'in' | 'out' | 'reset') => Promise<number>;
  toggleDevTools: () => Promise<boolean>;
  relaunch: () => Promise<void>;
  openLogs: () => Promise<string | null>;
  onMenu: (channel: AndroMenuChannel, handler: () => void) => () => void;
}

interface AndroDesktopBridge {
  isDesktop: true;
  openExternal: (url: string) => Promise<boolean>;
  connector: ConnectorBridge;
  consumeAuthCallback?: () => Promise<string | null>;
  onAuthCallback: (handler: (url: string) => void) => () => void;
  files?: AndroFilesBridge;
  dev?: AndroDevBridge;
}

interface Window {
  androDesktop?: AndroDesktopBridge;
}
