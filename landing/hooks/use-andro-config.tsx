import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  defaultEmbeddingConfig,
  defaultIntegrations,
  defaultMcpConfig,
  defaultSearchConfig,
} from "@/data/product-defaults";
import { fetchUserCredentials, type StoredCredentialView } from "@/lib/api/andromedia";
import { useTrial } from "@/hooks/use-trial";
import type {
  EmbeddingConfig,
  IntegrationConfig,
  IntegrationCredentials,
  IntegrationId,
  JiraCredentials,
  LinearCredentials,
  McpConfig,
  McpToolId,
  NotionCredentials,
  SearchConfig,
  SlackCredentials,
  SourceId,
  TestStatus,
  ZohoCredentials,
} from "@/types/product-config";
import { isSupportedIntegration, SUPPORTED_INTEGRATION_IDS } from "@/types/product-config";

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
  markSavedSecret: (id: IntegrationId, saved: boolean) => void;
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
  credentialsVaultEnabled: boolean;
}

const AndroConfigContext = createContext<AndroConfigContextValue | null>(null);

function sanitizeZohoCredentials(credentials: ZohoCredentials): ZohoCredentials {
  return {
    ...credentials,
    clientSecret: '',
    grantToken: '',
    // connectionId is a non-secret server handle and may persist for the browser session.
  };
}

function sanitizeSlackCredentials(credentials: {
  botToken: string;
  channels: string;
  lookbackDays: number;
}) {
  return { ...credentials, botToken: '' };
}

function sanitizeNotionCredentials(credentials: {
  apiKey: string;
  pageIds: string;
  databaseIds: string;
}) {
  return { ...credentials, apiKey: '' };
}

function sanitizeJiraCredentials(credentials: {
  baseUrl: string;
  email: string;
  apiToken: string;
  projectKeys: string;
  lookbackDays: number;
}) {
  return { ...credentials, apiToken: '' };
}

function sanitizeLinearCredentials(credentials: {
  apiKey: string;
  teamKeys: string;
  lookbackDays: number;
}) {
  return { ...credentials, apiKey: '' };
}

function sanitizeIntegrationsForPersist(integrations: IntegrationConfig[]): IntegrationConfig[] {
  return integrations.map((item) => {
    if (item.id === 'zoho') {
      return {
        ...item,
        credentials: sanitizeZohoCredentials(item.credentials as ZohoCredentials),
      };
    }
    if (item.id === 'slack') {
      return {
        ...item,
        credentials: sanitizeSlackCredentials(
          item.credentials as { botToken: string; channels: string; lookbackDays: number },
        ),
      };
    }
    if (item.id === 'notion') {
      return {
        ...item,
        credentials: sanitizeNotionCredentials(
          item.credentials as { apiKey: string; pageIds: string; databaseIds: string },
        ),
      };
    }
    if (item.id === 'jira') {
      return {
        ...item,
        credentials: sanitizeJiraCredentials(
          item.credentials as {
            baseUrl: string;
            email: string;
            apiToken: string;
            projectKeys: string;
            lookbackDays: number;
          },
        ),
      };
    }
    if (item.id === 'linear') {
      return {
        ...item,
        credentials: sanitizeLinearCredentials(
          item.credentials as { apiKey: string; teamKeys: string; lookbackDays: number },
        ),
      };
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


function applyStoredCredential(
  integration: IntegrationConfig,
  row: StoredCredentialView,
): IntegrationConfig {
  const metadata = row.metadata ?? {};
  if (integration.id === 'slack') {
    const creds = integration.credentials as SlackCredentials;
    return {
      ...integration,
      hasSavedSecret: row.hasSecret,
      status: row.hasSecret ? 'connected' : integration.status,
      credentials: {
        ...creds,
        channels:
          typeof metadata.channels === 'string'
            ? metadata.channels
            : Array.isArray(metadata.channels)
              ? (metadata.channels as string[]).join(', ')
              : creds.channels,
        lookbackDays:
          typeof metadata.lookbackDays === 'number'
            ? metadata.lookbackDays
            : creds.lookbackDays,
      },
    };
  }
  if (integration.id === 'notion') {
    const creds = integration.credentials as NotionCredentials;
    return {
      ...integration,
      hasSavedSecret: row.hasSecret,
      status: row.hasSecret ? 'connected' : integration.status,
      credentials: {
        ...creds,
        pageIds: Array.isArray(metadata.pageIds)
          ? (metadata.pageIds as string[]).join(', ')
          : typeof metadata.pageIds === 'string'
            ? metadata.pageIds
            : creds.pageIds,
        databaseIds: Array.isArray(metadata.databaseIds)
          ? (metadata.databaseIds as string[]).join(', ')
          : typeof metadata.databaseIds === 'string'
            ? metadata.databaseIds
            : creds.databaseIds,
      },
    };
  }
  if (integration.id === 'jira') {
    const creds = integration.credentials as JiraCredentials;
    return {
      ...integration,
      hasSavedSecret: row.hasSecret,
      status: row.hasSecret ? 'connected' : integration.status,
      credentials: {
        ...creds,
        baseUrl:
          typeof metadata.baseUrl === 'string' && metadata.baseUrl
            ? metadata.baseUrl
            : creds.baseUrl,
        projectKeys: Array.isArray(metadata.projectKeys)
          ? (metadata.projectKeys as string[]).join(', ')
          : typeof metadata.projectKeys === 'string'
            ? metadata.projectKeys
            : creds.projectKeys,
        lookbackDays:
          typeof metadata.lookbackDays === 'number'
            ? metadata.lookbackDays
            : creds.lookbackDays,
      },
    };
  }
  if (integration.id === 'linear') {
    const creds = integration.credentials as LinearCredentials;
    return {
      ...integration,
      hasSavedSecret: row.hasSecret,
      status: row.hasSecret ? 'connected' : integration.status,
      credentials: {
        ...creds,
        teamKeys: Array.isArray(metadata.teamKeys)
          ? (metadata.teamKeys as string[]).join(', ')
          : typeof metadata.teamKeys === 'string'
            ? metadata.teamKeys
            : creds.teamKeys,
        lookbackDays:
          typeof metadata.lookbackDays === 'number'
            ? metadata.lookbackDays
            : creds.lookbackDays,
      },
    };
  }
  if (integration.id === 'zoho') {
    const creds = integration.credentials as ZohoCredentials;
    const connectionId =
      typeof metadata.connectionId === 'string' ? metadata.connectionId : creds.connectionId;
    return {
      ...integration,
      hasSavedSecret: row.hasSecret || Boolean(connectionId),
      status: connectionId || row.hasSecret ? 'connected' : integration.status,
      credentials: {
        ...creds,
        connectionId: connectionId || creds.connectionId,
        region:
          metadata.region === 'com' || metadata.region === 'in'
            ? metadata.region
            : creds.region,
      },
    };
  }
  return { ...integration, hasSavedSecret: row.hasSecret };
}

export function AndroConfigProvider({
  children,
  onSave,
}: {
  children: ReactNode;
  onSave: () => void;
}) {
  const { serviceName, isTrial, trialId, getAccessToken, loading: trialLoading } = useTrial();
  const persisted = useMemo(() => loadPersistedConfig(), []);
  const [integrations, setIntegrations] = useState(() =>
    mergeIntegrations(defaultIntegrations, persisted.integrations),
  );
  const [mcp, setMcpState] = useState(() => mergeMcp(persisted.mcp));
  const [embedding] = useState(persisted.embedding ?? defaultEmbeddingConfig);
  const [search] = useState(persisted.search ?? defaultSearchConfig);
  const [credentialsVaultEnabled, setCredentialsVaultEnabled] = useState(false);

  useEffect(() => {
    if (trialLoading || !serviceName) {
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const token = isTrial ? await getAccessToken() : null;
        const response = await fetchUserCredentials({
          service: serviceName,
          trialId: isTrial ? trialId : serviceName,
          accessToken: token,
        });
        if (cancelled) {
          return;
        }
        setCredentialsVaultEnabled(response.enabled);
        if (!response.enabled || response.credentials.length === 0) {
          return;
        }
        const byProvider = new Map(
          response.credentials.map((row) => [row.provider, row] as const),
        );
        setIntegrations((prev) =>
          prev.map((item) => {
            const row = byProvider.get(item.id);
            return row ? applyStoredCredential(item, row) : item;
          }),
        );
      } catch {
        // Vault optional — keep sessionStorage config.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [getAccessToken, isTrial, serviceName, trialId, trialLoading]);

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
    markSavedSecret: (id, saved) => {
      setIntegrations((prev) =>
        prev.map((i) => (i.id === id ? { ...i, hasSavedSecret: saved } : i)),
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
    credentialsVaultEnabled,
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
