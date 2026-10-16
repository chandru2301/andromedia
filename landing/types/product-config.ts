export type SourceId =
  | 'CODE'
  | 'GIT'
  | 'SLACK'
  | 'NOTION'
  | 'JIRA'
  | 'LINEAR'
  | 'ZOHO'
  | 'DATADOG'
  | 'PAGERDUTY';

export type IntegrationId =
  | 'git'
  | 'slack'
  | 'teams'
  | 'notion'
  | 'confluence'
  | 'jira'
  | 'linear'
  | 'zoho'
  | 'datadog'
  | 'pagerduty';

export type ZohoRegion = 'com' | 'in';

export type ConnectionStatus = 'connected' | 'not configured' | 'disabled';
export type TestStatus = 'idle' | 'checking' | 'connected' | 'failed';
export type McpTransport = 'stdio' | 'sse';
export type EmbeddingProvider = 'ollama' | 'openai' | 'custom';

export type McpToolId =
  | 'andro_investigate'
  | 'andro_search'
  | 'andro_list_sources'
  | 'andro_get_file_history';

export const SUPPORTED_INTEGRATION_IDS: IntegrationId[] = [
  'git',
  'slack',
  'notion',
  'jira',
  'linear',
  'zoho',
];

export function isSupportedIntegration(id: IntegrationId): boolean {
  return SUPPORTED_INTEGRATION_IDS.includes(id);
}

export interface SourceOverview {
  id: SourceId;
  label: string;
  enabled: boolean;
  chunkCount: number;
  lastSync: string;
}

export interface GitCredentials {
  repoPath: string;
  branch: string;
  maxCommitHistoryDays: number;
  commitHistory: boolean;
  prDescriptions: boolean;
  symbolGraph: boolean;
}

export interface SlackCredentials {
  botToken: string;
  channels: string;
  lookbackDays: number;
}

export interface TeamsCredentials {
  tenantId: string;
  clientId: string;
  clientSecret: string;
}

export interface NotionCredentials {
  apiKey: string;
  pageIds: string;
  databaseIds: string;
}

export interface ConfluenceCredentials {
  baseUrl: string;
  username: string;
  apiToken: string;
}

export interface JiraCredentials {
  baseUrl: string;
  email: string;
  apiToken: string;
  projectKeys: string;
  lookbackDays: number;
}

export interface LinearCredentials {
  apiKey: string;
  teamKeys: string;
  lookbackDays: number;
}

export interface ZohoCredentials {
  clientId: string;
  clientSecret: string;
  grantToken: string;
  /** Server-side vault id — never a refresh token. */
  connectionId: string;
  region: ZohoRegion;
  lookbackDays: number;
  minQualityScore: number;
  embed: boolean;
}

export interface DatadogCredentials {
  apiKey: string;
  appKey: string;
  lookbackDays: number;
}

export interface PagerDutyCredentials {
  apiKey: string;
  lookbackDays: number;
}

export type IntegrationCredentials =
  | GitCredentials
  | SlackCredentials
  | TeamsCredentials
  | NotionCredentials
  | ConfluenceCredentials
  | JiraCredentials
  | LinearCredentials
  | ZohoCredentials
  | DatadogCredentials
  | PagerDutyCredentials;

export interface IntegrationConfig {
  id: IntegrationId;
  name: string;
  description: string;
  enabled: boolean;
  status: ConnectionStatus;
  testStatus: TestStatus;
  credentials: IntegrationCredentials;
  /** True when the backend vault has a secret for this provider (do not re-enter every session). */
  hasSavedSecret?: boolean;
}

export interface Repository {
  id: string;
  name: string;
  path: string;
  branch: string;
  chunkCount: number;
  lastIndexed: string;
  syncStatus: 'idle' | 'syncing' | 'done';
}

export interface McpConfig {
  transport: McpTransport;
  ssePort: number;
  tools: Record<McpToolId, boolean>;
  defaultSourceFilters: Partial<Record<SourceId, boolean>>;
}

export interface EmbeddingConfig {
  provider: EmbeddingProvider;
  ollamaBaseUrl: string;
  ollamaModel: string;
  openaiApiKey: string;
  openaiModel: string;
  customEndpoint: string;
  customApiKey: string;
  customDimensions: number;
}

export interface SearchConfig {
  hybrid: boolean;
  bm25Weight: number;
  vectorWeight: number;
  topK: number;
  gitBoost: boolean;
  sourceFilters: Partial<Record<SourceId, boolean>>;
}

export interface StatusBarState {
  localOnly: boolean;
  totalChunks: number;
  lastSync: string;
  apiUp: boolean;
}

export const SOURCE_IDS: SourceId[] = [
  'CODE',
  'GIT',
  'SLACK',
  'NOTION',
  'JIRA',
  'LINEAR',
  'ZOHO',
  'DATADOG',
  'PAGERDUTY',
];

/** Product-first sources shown in Global Settings (excludes unused connectors). */
export const PRODUCT_SOURCE_IDS: SourceId[] = [
  'CODE',
  'GIT',
  'SLACK',
  'NOTION',
  'JIRA',
  'LINEAR',
  'ZOHO',
];

export interface SourceFilterOption {
  id: SourceId;
  label: string;
  indexed: boolean;
  hint?: string;
}

export function isSourceId(value: string): value is SourceId {
  return (SOURCE_IDS as string[]).includes(value);
}

export function sourceIdsForIntegration(id: IntegrationId): SourceId[] {
  switch (id) {
    case 'git':
      return ['CODE', 'GIT'];
    case 'slack':
      return ['SLACK'];
    case 'notion':
      return ['NOTION'];
    case 'jira':
      return ['JIRA'];
    case 'linear':
      return ['LINEAR'];
    case 'zoho':
      return ['ZOHO'];
    default:
      return [];
  }
}

/**
 * Build the Global Settings source-filter checklist from live index types plus
 * enabled integrations (so users can pre-toggle before the first sync).
 */
export function buildSourceFilterOptions(input: {
  indexedSourceTypes?: string[] | null;
  enabledIntegrationIds?: IntegrationId[];
}): SourceFilterOption[] {
  const indexed = new Set(
    (input.indexedSourceTypes ?? [])
      .map((value) => value.trim().toUpperCase())
      .filter(isSourceId),
  );
  const fromEnabled = new Set<SourceId>();
  for (const integrationId of input.enabledIntegrationIds ?? []) {
    for (const source of sourceIdsForIntegration(integrationId)) {
      fromEnabled.add(source);
    }
  }

  const ordered: SourceId[] = [];
  const seen = new Set<SourceId>();
  const push = (id: SourceId) => {
    if (seen.has(id)) {
      return;
    }
    seen.add(id);
    ordered.push(id);
  };

  for (const id of PRODUCT_SOURCE_IDS) {
    push(id);
  }
  for (const id of indexed) {
    push(id);
  }
  for (const id of fromEnabled) {
    push(id);
  }

  return ordered.map((id) => {
    const isIndexed = indexed.has(id);
    return {
      id,
      label: id,
      indexed: isIndexed,
      hint: isIndexed ? undefined : 'not indexed',
    };
  });
}

export function pruneSourceFilters(
  filters: Partial<Record<SourceId, boolean>>,
  options: SourceFilterOption[],
): Partial<Record<SourceId, boolean>> {
  const next: Partial<Record<SourceId, boolean>> = {};
  for (const option of options) {
    const existing = filters[option.id];
    next[option.id] =
      typeof existing === 'boolean' ? existing : option.indexed ? true : false;
  }
  return next;
}

export function enabledSourceTypesFromFilters(
  filters: Partial<Record<SourceId, boolean>>,
): SourceId[] {
  return (Object.entries(filters) as [SourceId, boolean | undefined][])
    .filter(([, enabled]) => enabled === true)
    .map(([id]) => id);
}

export const MCP_TOOLS: { id: McpToolId; label: string; description: string }[] = [
  {
    id: 'andro_investigate',
    label: 'andro_investigate',
    description: 'Ask grounded questions over code, git history, and Slack',
  },
  {
    id: 'andro_search',
    label: 'andro_search',
    description: 'Hybrid BM25 + vector search with source provenance',
  },
  {
    id: 'andro_list_sources',
    label: 'andro_list_sources',
    description: 'List indexed sources, chunk counts, and last sync',
  },
  {
    id: 'andro_get_file_history',
    label: 'andro_get_file_history',
    description:
      'Heuristic GIT commit search for a path (not a true git log; needs GIT indexed)',
  },
];
