import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  defaultEmbeddingConfig,
  defaultIntegrations,
  defaultMcpConfig,
  defaultSearchConfig,
} from '@/data/defaults';
import type {
  EmbeddingConfig,
  IntegrationConfig,
  IntegrationCredentials,
  IntegrationId,
  McpConfig,
  McpToolId,
  SearchConfig,
  SourceId,
  TestStatus,
} from '@/types/config';
import { isSupportedIntegration, SUPPORTED_INTEGRATION_IDS } from '@/types/config';

const STORAGE_KEY = 'andro-config-v2';

interface PersistedConfig {
  integrations?: IntegrationConfig[];
  mcp?: McpConfig;
  embedding?: EmbeddingConfig;
  search?: SearchConfig;
}

interface AndroConfigContextValue {
  integrations: IntegrationConfig[];
  setIntegrationEnabled: (id: IntegrationId, enabled: boolean) => void;
  updateIntegrationCredentials: (id: IntegrationId, credentials: IntegrationCredentials) => void;
  setIntegrationTestStatus: (id: IntegrationId, status: TestStatus) => void;
  setIntegrationStatus: (
    id: IntegrationId,
    status: IntegrationConfig['status'],
    testStatus?: TestStatus,
  ) => void;
  mcp: McpConfig;
  setMcp: (patch: Partial<McpConfig>) => void;
  setMcpTool: (tool: McpToolId, enabled: boolean) => void;
  setMcpSourceFilter: (source: SourceId, enabled: boolean) => void;
  embedding: EmbeddingConfig;
  search: SearchConfig;
  overviewStats: {
    sourcesConnected: number;
    totalSources: number;
  };
  save: (nextIntegrations?: IntegrationConfig[], nextMcp?: McpConfig) => void;
}

const AndroConfigContext = createContext<AndroConfigContextValue | null>(null);

// Jira/Linear/Zoho no longer carry any secret fields client-side (OAuth-only — the server vaults
// the token, the browser only ever sees a connectionId for Zoho). Slack and Notion still keep a
// manual-token field on their credential type for now, so those still get scrubbed before persist.
function sanitizeIntegrationsForPersist(integrations: IntegrationConfig[]): IntegrationConfig[] {
  return integrations.map((item) => {
    if (item.id === 'slack') {
      const c = item.credentials as { botToken: string; channels: string; lookbackDays: number };
      return { ...item, credentials: { ...c, botToken: '' } };
    }
    if (item.id === 'notion') {
      const c = item.credentials as { apiKey: string; pageIds: string; databaseIds: string };
      return { ...item, credentials: { ...c, apiKey: '' } };
    }
    return item;
  });
}

function mergeIntegrations(
  defaults: IntegrationConfig[],
  stored: IntegrationConfig[] | undefined,
): IntegrationConfig[] {
  if (!stored || stored.length === 0) {
    return defaults;
  }
  const byId = new Map(stored.map((item) => [item.id, item]));
  return defaults.map((fallback) => {
    const saved = byId.get(fallback.id);
    if (!saved) {
      return fallback;
    }
    if (!isSupportedIntegration(fallback.id)) {
      return { ...fallback, enabled: false, status: 'disabled' };
    }
    return {
      ...fallback,
      ...saved,
      enabled: saved.enabled ?? fallback.enabled,
      status: saved.status === 'disabled' ? 'not configured' : saved.status,
      credentials: { ...fallback.credentials, ...saved.credentials },
    };
  });
}

function mergeMcp(stored: McpConfig | undefined): McpConfig {
  if (!stored) {
    return defaultMcpConfig;
  }
  return {
    ...defaultMcpConfig,
    ...stored,
    tools: { ...defaultMcpConfig.tools, ...stored.tools },
    defaultSourceFilters: {
      ...defaultMcpConfig.defaultSourceFilters,
      ...stored.defaultSourceFilters,
    },
  };
}

function loadPersistedConfig(): PersistedConfig {
  try {
    const fromSession = sessionStorage.getItem(STORAGE_KEY);
    if (fromSession) {
      return JSON.parse(fromSession) as PersistedConfig;
    }
    // One-time migrate away from localStorage so credentials do not survive browser restarts.
    const fromLocal = localStorage.getItem(STORAGE_KEY);
    if (fromLocal) {
      try {
        sessionStorage.setItem(STORAGE_KEY, fromLocal);
      } catch {
        // ignore quota / private mode
      }
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // ignore
      }
      return JSON.parse(fromLocal) as PersistedConfig;
    }
    return {};
  } catch {
    return {};
  }
}

export function AndroConfigProvider({
  children,
  onSave,
}: {
  children: ReactNode;
  onSave: () => void;
}) {
  const persisted = useMemo(() => loadPersistedConfig(), []);
  const [integrations, setIntegrations] = useState(() =>
    mergeIntegrations(defaultIntegrations, persisted.integrations),
  );
  const [mcp, setMcpState] = useState(() => mergeMcp(persisted.mcp));
  const [embedding] = useState(persisted.embedding ?? defaultEmbeddingConfig);
  const [search] = useState(persisted.search ?? defaultSearchConfig);

  const overviewStats = useMemo(() => {
    const supported = integrations.filter((i) => isSupportedIntegration(i.id));
    const connected = supported.filter((i) => i.status === 'connected').length;
    return {
      sourcesConnected: connected,
      totalSources: SUPPORTED_INTEGRATION_IDS.length,
    };
  }, [integrations]);

  const persist = useCallback(
    (nextIntegrations: IntegrationConfig[], nextMcp: McpConfig) => {
      const payload: PersistedConfig = {
        integrations: sanitizeIntegrationsForPersist(nextIntegrations),
        mcp: nextMcp,
        embedding,
        search,
      };
      try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // ignore quota / private mode
      }
    },
    [embedding, search],
  );

  const save = useCallback(
    (nextIntegrations?: IntegrationConfig[], nextMcp?: McpConfig) => {
      const integrationsToSave = nextIntegrations ?? integrations;
      const mcpToSave = nextMcp ?? mcp;
      if (nextIntegrations) {
        setIntegrations(nextIntegrations);
      }
      if (nextMcp) {
        setMcpState(nextMcp);
      }
      persist(integrationsToSave, mcpToSave);
      onSave();
    },
    [persist, integrations, mcp, onSave],
  );

  const value: AndroConfigContextValue = {
    integrations,
    setIntegrationEnabled: (id, enabled) => {
      if (!isSupportedIntegration(id)) {
        return;
      }
      setIntegrations((prev) =>
        prev.map((i) =>
          i.id === id
            ? {
                ...i,
                enabled,
                status: enabled
                  ? i.status === 'disabled'
                    ? 'not configured'
                    : i.status
                  : 'disabled',
              }
            : i,
        ),
      );
    },
    updateIntegrationCredentials: (id, credentials) => {
      setIntegrations((prev) =>
        prev.map((i) => (i.id === id ? { ...i, credentials } : i)),
      );
    },
    setIntegrationTestStatus: (id, testStatus) => {
      setIntegrations((prev) =>
        prev.map((i) =>
          i.id === id
            ? {
                ...i,
                testStatus,
                status:
                  testStatus === 'connected'
                    ? 'connected'
                    : testStatus === 'failed'
                      ? 'not configured'
                      : i.status,
              }
            : i,
        ),
      );
    },
    setIntegrationStatus: (id, status, testStatus) => {
      setIntegrations((prev) =>
        prev.map((i) =>
          i.id === id
            ? {
                ...i,
                status,
                testStatus: testStatus ?? i.testStatus,
              }
            : i,
        ),
      );
    },
    mcp,
    setMcp: (patch) => setMcpState((prev) => ({ ...prev, ...patch })),
    setMcpTool: (tool, enabled) =>
      setMcpState((prev) => ({ ...prev, tools: { ...prev.tools, [tool]: enabled } })),
    setMcpSourceFilter: (source, enabled) =>
      setMcpState((prev) => ({
        ...prev,
        defaultSourceFilters: { ...prev.defaultSourceFilters, [source]: enabled },
      })),
    embedding,
    search,
    overviewStats,
    save,
  };

  return <AndroConfigContext.Provider value={value}>{children}</AndroConfigContext.Provider>;
}

export function useAndroConfig() {
  const ctx = useContext(AndroConfigContext);
  if (!ctx) {
    throw new Error('useAndroConfig must be used within AndroConfigProvider');
  }
  return ctx;
}

export function buildClaudeSnippet(mcp: McpConfig): string {
  const enabledSources = Object.entries(mcp.defaultSourceFilters)
    .filter(([, enabled]) => enabled === true)
    .map(([source]) => source);
  if (mcp.transport === 'stdio') {
    return JSON.stringify(
      {
        mcpServers: {
          andro: {
            command: 'node',
            args: ['/home/chan/Documents/andro-mcp/server.js'],
            env: {
              ANDRO_BASE_URL: 'http://localhost:8083',
              ANDRO_SERVICE: 'local',
              ...(enabledSources.length > 0
                ? { ANDRO_DEFAULT_SOURCE_TYPES: enabledSources.join(',') }
                : {}),
            },
          },
        },
      },
      null,
      2,
    );
  }
  return JSON.stringify(
    {
      mcpServers: {
        andro: {
          url: `http://localhost:${mcp.ssePort}/sse`,
          ...(enabledSources.length > 0
            ? { headers: { 'X-Andro-Source-Types': enabledSources.join(',') } }
            : {}),
        },
      },
    },
    null,
    2,
  );
}
