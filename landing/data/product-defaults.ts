import type {
  EmbeddingConfig,
  IntegrationConfig,
  McpConfig,
  SearchConfig,
  StatusBarState,
} from "@/types/product-config";
import { PRODUCT_INTEGRATION_META } from "@/data/product-integration-meta";

export const defaultIntegrations: IntegrationConfig[] = [
  {
    id: 'git',
    name: 'Git / GitHub',
    description: PRODUCT_INTEGRATION_META.git.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: {
      repoPath: '',
      branch: 'main',
      maxCommitHistoryDays: 90,
      commitHistory: true,
      prDescriptions: false,
      symbolGraph: true,
    },
  },
  {
    id: 'slack',
    name: 'Slack',
    description: PRODUCT_INTEGRATION_META.slack.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: { botToken: '', channels: '', lookbackDays: 90 },
  },
  {
    id: 'teams',
    name: 'Microsoft Teams',
    description: PRODUCT_INTEGRATION_META.teams.description,
    enabled: false,
    status: 'disabled',
    testStatus: 'idle',
    credentials: { tenantId: '', clientId: '', clientSecret: '' },
  },
  {
    id: 'notion',
    name: 'Notion',
    description: PRODUCT_INTEGRATION_META.notion.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: { apiKey: '', pageIds: '', databaseIds: '' },
  },
  {
    id: 'confluence',
    name: 'Confluence',
    description: PRODUCT_INTEGRATION_META.confluence.description,
    enabled: false,
    status: 'disabled',
    testStatus: 'idle',
    credentials: { baseUrl: '', username: '', apiToken: '' },
  },
  {
    id: 'jira',
    name: 'Jira',
    description: PRODUCT_INTEGRATION_META.jira.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: {
      baseUrl: '',
      email: '',
      apiToken: '',
      projectKeys: '',
      lookbackDays: 90,
    },
  },
  {
    id: 'linear',
    name: 'Linear',
    description: PRODUCT_INTEGRATION_META.linear.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: { apiKey: '', teamKeys: '', lookbackDays: 90 },
  },
  {
    id: 'zoho',
    name: 'Zoho CRM',
    description: PRODUCT_INTEGRATION_META.zoho.description,
    enabled: true,
    status: 'not configured',
    testStatus: 'idle',
    credentials: {
      clientId: '',
      clientSecret: '',
      grantToken: '',
      connectionId: '',
      region: 'in',
      lookbackDays: 90,
      minQualityScore: 0.35,
      embed: false,
    },
  },
  {
    id: 'datadog',
    name: 'Datadog',
    description: PRODUCT_INTEGRATION_META.datadog.description,
    enabled: false,
    status: 'disabled',
    testStatus: 'idle',
    credentials: { apiKey: '', appKey: '', lookbackDays: 30 },
  },
  {
    id: 'pagerduty',
    name: 'PagerDuty',
    description: PRODUCT_INTEGRATION_META.pagerduty.description,
    enabled: false,
    status: 'disabled',
    testStatus: 'idle',
    credentials: { apiKey: '', lookbackDays: 14 },
  },
];

export const defaultMcpConfig: McpConfig = {
  transport: 'stdio',
  ssePort: 3100,
  tools: {
    andro_investigate: true,
    andro_search: true,
    andro_list_sources: true,
    // Off by default: demo corpus has no GIT/CODE; exposing this looks broken to agents.
    andro_get_file_history: false,
  },
  defaultSourceFilters: {
    CODE: true,
    GIT: true,
    SLACK: true,
    NOTION: false,
    JIRA: false,
    LINEAR: false,
    ZOHO: false,
  },
};

/** Kept for persisted config merge only — not editable against the server. */
export const defaultEmbeddingConfig: EmbeddingConfig = {
  provider: 'ollama',
  ollamaBaseUrl: 'http://localhost:11434',
  ollamaModel: 'nomic-embed-text',
  openaiApiKey: '',
  openaiModel: 'text-embedding-3-small',
  customEndpoint: '',
  customApiKey: '',
  customDimensions: 1536,
};

/** Kept for persisted config merge only — not editable against the server. */
export const defaultSearchConfig: SearchConfig = {
  hybrid: true,
  bm25Weight: 0.6,
  vectorWeight: 1.0,
  topK: 8,
  gitBoost: true,
  sourceFilters: {
    CODE: true,
    GIT: true,
    SLACK: true,
    NOTION: false,
    JIRA: false,
    LINEAR: false,
    ZOHO: false,
  },
};

export const defaultStatusBar: StatusBarState = {
  localOnly: true,
  totalChunks: 0,
  lastSync: 'never',
  apiUp: false,
};
