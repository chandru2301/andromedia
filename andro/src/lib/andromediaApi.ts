const DEFAULT_API_BASE = 'http://localhost:8083';

export type RequestAuthMode = 'trial' | 'local_pro';

export interface RequestAuthState {
  mode: RequestAuthMode;
  trialId: string | null;
  getAccessToken: () => Promise<string | null>;
}

let requestAuth: RequestAuthState = {
  mode: 'local_pro',
  trialId: null,
  getAccessToken: async () => null,
};

/**
 * The workspace the UI is currently pointed at.
 *
 * Module-level (like `requestAuth`) so every API helper picks it up without
 * prop-drilling. In trial mode this is ignored — a trial can only ever see its
 * own workspace, which the backend enforces.
 */
let activeWorkspace: string | null = null;

export function setActiveWorkspace(service: string | null): void {
  activeWorkspace = service && service.trim() ? service.trim() : null;
}

export function getActiveWorkspace(): string | null {
  return activeWorkspace;
}

/** Bound by TrialSessionProvider so API helpers attach Trial headers without prop-drilling. */
export function setRequestAuth(next: RequestAuthState): void {
  requestAuth = next;
}

export function getRequestAuth(): RequestAuthState {
  return requestAuth;
}

/** Hosted / configured API (Trial). Used by trial bootstrap even before mode headers are set. */
export function hostedAndromediaApiBase(): string {
  const fromEnv = import.meta.env.VITE_ANDROMEDIA_API_URL as string | undefined;
  if (fromEnv && fromEnv.trim()) {
    return fromEnv.replace(/\/$/, '');
  }
  return DEFAULT_API_BASE;
}

/** Local core for Local Pro. */
export function localAndromediaApiBase(): string {
  const local =
    (import.meta.env.VITE_LOCAL_ANDROMEDIA_API_URL as string | undefined)?.trim() ||
    DEFAULT_API_BASE;
  return local.replace(/\/$/, '');
}

/**
 * Active API base for the current mode. Trial → hosted; Local Pro → localhost.
 * Packaged builds can bake a hosted VITE_ANDROMEDIA_API_URL for Trial without
 * breaking Continue locally.
 */
export function andromediaApiBase(): string {
  return requestAuth.mode === 'trial' ? hostedAndromediaApiBase() : localAndromediaApiBase();
}

/** Active Lucene/API service name for the current mode. */
export function resolveService(override?: string | null): string {
  if (override != null && String(override).trim()) {
    return String(override).trim();
  }
  // Trial is always scoped to the trial's own workspace; the backend rejects
  // anything else, so a manual selection must not override it.
  if (requestAuth.mode === 'trial' && requestAuth.trialId) {
    return requestAuth.trialId;
  }
  if (activeWorkspace) {
    return activeWorkspace;
  }
  return 'local';
}

export async function authHeaders(
  extra?: HeadersInit,
): Promise<Record<string, string>> {
  const headers: Record<string, string> = {};
  if (extra) {
    new Headers(extra).forEach((value, key) => {
      headers[key] = value;
    });
  }
  if (requestAuth.mode === 'trial' && requestAuth.trialId) {
    headers['X-Trial-Id'] = requestAuth.trialId;
    const token = await requestAuth.getAccessToken();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
  }
  return headers;
}

/** Authenticated fetch against the Andromedia API base. */
export async function andromediaFetch(
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const headers = await authHeaders(init?.headers);
  return fetch(`${andromediaApiBase()}${path.startsWith('/') ? path : `/${path}`}`, {
    ...init,
    headers,
  });
}

export interface TrialConfig {
  enabled: boolean;
  maxQueries?: number;
  chatModel?: string;
}

export interface TrialStatus {
  trialId: string;
  queriesUsed: number;
  maxQueries: number;
  remaining: number;
}

export class TrialLimitError extends Error {
  readonly code = 'TRIAL_LIMIT';
  readonly queriesUsed: number;
  readonly maxQueries: number;

  constructor(message: string, queriesUsed: number, maxQueries: number) {
    super(message);
    this.name = 'TrialLimitError';
    this.queriesUsed = queriesUsed;
    this.maxQueries = maxQueries;
  }
}

export async function fetchTrialConfig(): Promise<TrialConfig> {
  const response = await fetch(`${hostedAndromediaApiBase()}/api/v1/trial/config`);
  return readJson<TrialConfig>(response);
}

export async function startTrialSession(
  trialId: string,
  accessToken?: string | null,
): Promise<TrialStatus> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  const response = await fetch(`${hostedAndromediaApiBase()}/api/v1/trial/start`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ trialId }),
  });
  const body = await readJson<TrialStatus>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Trial start failed (${response.status})`);
  }
  return body;
}

export async function fetchTrialStatus(
  trialId: string,
  accessToken?: string | null,
): Promise<TrialStatus> {
  const headers: Record<string, string> = {};
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  const response = await fetch(
    `${hostedAndromediaApiBase()}/api/v1/trial/status/${encodeURIComponent(trialId)}`,
    Object.keys(headers).length > 0 ? { headers } : undefined,
  );
  const body = await readJson<TrialStatus>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Trial status failed (${response.status})`);
  }
  return body;
}

export interface SlackIndexJobResponse {
  jobId: string;
  service: string;
  status: string;
  error?: string;
  stats?: Record<string, unknown>;
}

export function isNoNewSlackThreads(job: SlackIndexJobResponse): boolean {
  const error = job.error ?? '';
  if (error.includes('No Slack threads found') || error.includes('No new Slack threads')) {
    return true;
  }
  if (job.status !== 'completed') {
    return false;
  }
  const threads = job.stats?.threadsIndexed;
  return typeof threads === 'number' ? threads === 0 : false;
}

export interface SlackTestResponse {
  ok: boolean;
  team?: string;
  teamId?: string;
  user?: string;
  resolvedChannels?: string[];
  missingChannels?: string[];
  error?: string;
}

export interface GoogleChatIndexJobResponse {
  jobId: string;
  service: string;
  status: string;
  error?: string;
  stats?: Record<string, unknown>;
}

export function isNoNewGoogleChatThreads(job: GoogleChatIndexJobResponse): boolean {
  if (job.error?.includes('No new Google Chat threads')) {
    return true;
  }
  if (job.status !== 'completed') {
    return false;
  }
  const threads = job.stats?.threadsIndexed;
  return typeof threads === 'number' ? threads === 0 : false;
}

export interface GoogleChatTestResponse {
  ok: boolean;
  totalSpaces?: number;
  resolvedSpaces?: string[];
  missingSpaces?: string[];
  error?: string;
}

async function readJson<T>(response: Response): Promise<T & { error?: string; code?: string }> {
  return (await response.json().catch(() => ({}))) as T & { error?: string; code?: string };
}

export type OAuthProviderId = 'slack' | 'notion' | 'google_chat' | 'linear' | 'zoho' | 'jira';

export interface OAuthStatus {
  provider: string;
  configured: boolean;
  connected: boolean;
  metadata?: Record<string, unknown>;
}

export function oauthAuthorizeUrl(provider: OAuthProviderId, trialId?: string): string {
  const id = resolveService(trialId);
  return `${andromediaApiBase()}/api/v1/oauth/${provider}/authorize?trialId=${encodeURIComponent(id)}`;
}

export async function getOAuthStatus(
  provider: OAuthProviderId,
  trialId?: string,
): Promise<OAuthStatus> {
  const id = resolveService(trialId);
  const response = await andromediaFetch(
    `/api/v1/oauth/${provider}/status?trialId=${encodeURIComponent(id)}`,
  );
  const body = await readJson<OAuthStatus>(response);
  if (!response.ok) {
    throw new AndromediaApiError(body.error ?? 'Failed to load connection status', response.status);
  }
  return body;
}

/** HTTP error from Andromedia API with optional status/code for callers. */
export class AndromediaApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'AndromediaApiError';
    this.status = status;
    this.code = code;
  }
}

function throwHttpError(
  body: { error?: string; code?: string },
  status: number,
  fallback: string,
): never {
  throw new AndromediaApiError(body.error ?? fallback, status, body.code);
}

function isRetryableJobPollError(error: unknown): boolean {
  if (error instanceof AndromediaApiError) {
    return error.status === 429 || error.status === 503;
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return msg.includes('rate limit') || msg.includes('failed to fetch');
  }
  return false;
}

export type ChatProviderId = 'groq' | 'deepseek' | 'openrouter' | 'ollama';

export interface ChatProviderStatus {
  id: ChatProviderId;
  label: string;
  model: string;
  configured: boolean;
  apiKeyConfigured: boolean;
  active: boolean;
  availableModels?: string[];
}

export interface ChatProviderSelection {
  activeProvider: ChatProviderId;
  providers: ChatProviderStatus[];
}

export interface ChatProviderUpdate {
  provider: ChatProviderId;
  model?: string;
  apiKey?: string;
}

export async function getChatProviderSelection(input?: {
  trialId?: string | null;
}): Promise<ChatProviderSelection> {
  const headers = await authHeaders();
  if (input?.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  const response = await andromediaFetch(`/api/v1/settings/chat-provider`, {
    headers,
  });
  const body = await readJson<ChatProviderSelection>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Chat provider lookup failed (${response.status})`);
  }
  return body;
}

export async function updateChatProvider(
  input: ChatProviderId | ChatProviderUpdate,
  auth?: { trialId?: string | null },
): Promise<ChatProviderSelection> {
  const payload: ChatProviderUpdate =
    typeof input === 'string' ? { provider: input } : input;
  const headers = await authHeaders({ 'Content-Type': 'application/json' });
  if (auth?.trialId) {
    headers['X-Trial-Id'] = auth.trialId;
  }
  const response = await andromediaFetch(`/api/v1/settings/chat-provider`, {
    method: 'PUT',
    headers,
    body: JSON.stringify({
      provider: payload.provider,
      model: payload.model,
      apiKey: payload.apiKey,
    }),
  });
  const body = await readJson<ChatProviderSelection>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Chat provider update failed (${response.status})`);
  }
  return body;
}

export async function startSlackIndex(input: {
  service?: string;
  botToken: string;
  channels: string;
  embed?: boolean;
  lookbackDays?: number;
}): Promise<SlackIndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/slack`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      botToken: input.botToken,
      channels: input.channels,
      embed: input.embed ?? false,
      lookbackDays: input.lookbackDays ?? 90,
    }),
  });

  const body = await readJson<SlackIndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Slack index failed (${response.status})`);
  }
  return body;
}

export async function getSlackIndexJob(jobId: string): Promise<SlackIndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/slack/${jobId}`);
  const body = await readJson<SlackIndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Slack job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollSlackIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<SlackIndexJobResponse> {
  return pollJob(getSlackIndexJob, jobId, {
    ...options,
    timeoutMs: options?.timeoutMs ?? 120_000,
    timeoutMessage: 'Slack indexing timed out — check the job status later',
  });
}

export async function testSlackConnection(input: {
  botToken: string;
  channels: string;
}): Promise<SlackTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/slack/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      botToken: input.botToken,
      channels: input.channels,
    }),
  });

  const body = await readJson<SlackTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Slack connection test failed (${response.status})`);
  }
  return body;
}

export async function startGoogleChatIndex(input: {
  service?: string;
  accessToken?: string;
  spaces: string;
  embed?: boolean;
  lookbackDays?: number;
}): Promise<GoogleChatIndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/google-chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      accessToken: input.accessToken ?? '',
      spaces: input.spaces,
      embed: input.embed ?? false,
      lookbackDays: input.lookbackDays ?? 90,
    }),
  });

  const body = await readJson<GoogleChatIndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Google Chat index failed (${response.status})`);
  }
  return body;
}

export async function getGoogleChatIndexJob(jobId: string): Promise<GoogleChatIndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/google-chat/${jobId}`);
  const body = await readJson<GoogleChatIndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Google Chat job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollGoogleChatIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<GoogleChatIndexJobResponse> {
  return pollJob(getGoogleChatIndexJob, jobId, {
    ...options,
    timeoutMs: options?.timeoutMs ?? 120_000,
    timeoutMessage: 'Google Chat indexing timed out — check the job status later',
  });
}

export async function testGoogleChatConnection(input: {
  service?: string;
  accessToken?: string;
  spaces: string;
}): Promise<GoogleChatTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/google-chat/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      accessToken: input.accessToken ?? '',
      spaces: input.spaces,
    }),
  });

  const body = await readJson<GoogleChatTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Google Chat connection test failed (${response.status})`);
  }
  return body;
}

export interface NotionTestResponse {
  ok: boolean;
  workspace?: string;
  user?: string;
  accessiblePageIds?: string[];
  missingPageIds?: string[];
  error?: string;
}

export async function testNotionConnection(input: {
  apiKey: string;
  pageIds?: string;
}): Promise<NotionTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/notion/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      apiKey: input.apiKey,
      pageIds: input.pageIds ?? '',
    }),
  });
  const body = await readJson<NotionTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Notion connection test failed (${response.status})`);
  }
  return body;
}

export async function checkAndromediaHealth(): Promise<boolean> {
  try {
    const response = await andromediaFetch(`/api/v1/health`);
    if (!response.ok) {
      return false;
    }
    const body = (await response.json()) as { status?: string };
    return body.status === 'UP';
  } catch {
    return false;
  }
}

export interface IndexOverviewResponse {
  service: string;
  indexPath: string;
  exists: boolean;
  totalChunks: number;
  sourcesConnected: number;
  sourceTypes: string[];
  lastSyncAt: string | null;
  indexSizeBytes: number;
}

/** One entry in the workspace list. Mirrors GET /api/v1/workspaces. */
export interface WorkspaceSummary {
  service: string;
  exists: boolean;
  totalChunks: number;
  sourceTypes: string[];
  lastSyncAt: string | null;
  indexSizeBytes: number;
}

/**
 * Every workspace the backend knows about.
 *
 * Deliberately does NOT go through `resolveService` — this is the call that
 * populates the picker, so it must not be filtered by the current selection.
 */
export async function fetchWorkspaces(): Promise<WorkspaceSummary[]> {
  const response = await andromediaFetch('/api/v1/workspaces');
  const body = await readJson<{ workspaces?: WorkspaceSummary[]; error?: string }>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Workspace list failed (${response.status})`);
  }
  return Array.isArray(body.workspaces) ? body.workspaces : [];
}

/** A project directory the API is allowed to index. */
export interface WorkspaceCandidate {
  name: string;
  /** Path as the API sees it — this is what an index request must send. */
  path: string;
  markers: string[];
}

/**
 * Projects available under the read-only workspace mount.
 *
 * These live under the mount rather than anywhere on the host: the API runs in
 * a container, so it can only index what has been mounted into it. Empty when
 * the mount holds no recognisable projects.
 */
export async function fetchWorkspaceCandidates(): Promise<WorkspaceCandidate[]> {
  const response = await andromediaFetch('/api/v1/workspaces/candidates');
  const body = await readJson<{ candidates?: WorkspaceCandidate[]; error?: string }>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Could not list projects (${response.status})`);
  }
  return Array.isArray(body.candidates) ? body.candidates : [];
}

export async function fetchIndexOverview(
  service?: string,
): Promise<IndexOverviewResponse> {
  const svc = resolveService(service);
  const response = await andromediaFetch(
    `/api/v1/index/services/${encodeURIComponent(svc)}/overview`,
  );
  const body = await readJson<IndexOverviewResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Index overview failed (${response.status})`);
  }
  return body;
}

export interface InvestigationEvidence {
  source: string;
  name: string;
  score: number;
  structured: Record<string, string>;
  flags: string[];
  contacts: string[];
  notes: string[];
  narrative: string;
}

export interface InvestigationAnswer {
  title: string;
  recordName: string | null;
  primaryFields: Record<string, string>;
  reasons: string[];
  scoreBreakdown: Record<string, number>;
  modelId: string;
  trustState:
    | 'structured'
    | 'deterministic'
    | 'conflict_adjudicated'
    | 'grounded'
    | 'demo_only'
    | 'insufficient_data'
    | string;
  intent: 'QUALIFICATION' | 'RANK' | 'REASON' | 'HIGHEST_PROBABILITY' | 'GENERAL' | string;
  summaryText: string | null;
}

export interface InvestigationResponse {
  summary: string;
  details: string;
  metadata: {
    service?: string;
    indexPath?: string;
    referenceCount?: number;
    context?: Record<string, unknown>;
    conversationBag?: Record<string, unknown>;
    qualificationIntent?: boolean;
    answerIntent?: 'QUALIFICATION' | 'RANK' | 'REASON' | 'HIGHEST_PROBABILITY' | 'GENERAL' | string;
    qualificationModel?: string;
    qualificationTrustState?: string;
  };
  evidence: InvestigationEvidence[];
  answer?: InvestigationAnswer | null;
}

export async function investigateCrm(input: {
  query: string;
  service?: string;
  conversationBag?: Record<string, unknown> | null;
}): Promise<InvestigationResponse> {
  const context: Record<string, unknown> = {
    trustMode: 'structured-evidence',
  };
  if (input.conversationBag && Object.keys(input.conversationBag).length > 0) {
    context.conversationBag = input.conversationBag;
  }
  const response = await andromediaFetch('/api/v1/investigate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      query: input.query,
      context,
    }),
  });
  const body = await readJson<InvestigationResponse>(response);
  if (!response.ok) {
    if (body.code === 'TRIAL_LIMIT') {
      throw new TrialLimitError(
        body.error ?? 'Trial query limit reached',
        0,
        0,
      );
    }
    if (response.status === 401) {
      throw new Error('Session expired. Sign in again to continue Trial.');
    }
    throw new Error(body.error ?? `CRM investigation failed (${response.status})`);
  }
  return {
    ...body,
    evidence: body.evidence ?? [],
    answer: body.answer ?? null,
  };
}

/** Live progress event streamed from POST /api/v1/investigate/stream. */
export interface InvestigationProgressEvent {
  type: 'progress' | 'result' | 'error' | 'done';
  step?: string;
  status?: 'running' | 'done';
  detail?: string;
  sourceType?: string;
  result?: InvestigationResponse;
  error?: { code: string; message: string };
}

/**
 * Streaming twin of {@link investigateCrm}: resolves on the terminal `result` event while
 * emitting every real progress event to `onEvent` as it happens.
 */
export async function investigateCrmStream(input: {
  query: string;
  service?: string;
  conversationBag?: Record<string, unknown> | null;
  sourceTypes?: string[];
  onEvent?: (event: InvestigationProgressEvent) => void;
}): Promise<InvestigationResponse> {
  const context: Record<string, unknown> = {
    trustMode: 'structured-evidence',
  };
  if (input.conversationBag && Object.keys(input.conversationBag).length > 0) {
    context.conversationBag = input.conversationBag;
  }
  const response = await andromediaFetch('/api/v1/investigate/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      query: input.query,
      context,
      sourceTypes: input.sourceTypes,
    }),
  });

  const contentType = response.headers.get('content-type') ?? '';
  if (!response.ok || !contentType.includes('text/event-stream') || !response.body) {
    const body = await readJson<
      InvestigationResponse & { code?: string; queriesUsed?: number; maxQueries?: number }
    >(response);
    if (body.code === 'TRIAL_LIMIT' || (response.status === 429 && body.code === 'TRIAL_LIMIT')) {
      throw new TrialLimitError(
        body.error ?? 'Trial query limit reached',
        body.queriesUsed ?? 0,
        body.maxQueries ?? 0,
      );
    }
    if (response.status === 401) {
      throw new Error('Session expired. Sign in again to continue Trial.');
    }
    throw new Error(body.error ?? `CRM investigation failed (${response.status})`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalResult: InvestigationResponse | null = null;
  let streamError: Error | null = null;

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let boundary = buffer.indexOf('\n\n');
    while (boundary !== -1) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const dataLine = frame.split('\n').find((line) => line.startsWith('data:'));
      if (dataLine) {
        const payload = dataLine.slice('data:'.length).trim();
        if (payload) {
          try {
            const event = JSON.parse(payload) as InvestigationProgressEvent;
            input.onEvent?.(event);
            if (event.type === 'result' && event.result) {
              finalResult = event.result;
            } else if (event.type === 'error') {
              if (event.error?.code === 'TRIAL_LIMIT') {
                streamError = new TrialLimitError(
                  event.error.message ?? 'Trial query limit reached',
                  0,
                  0,
                );
              } else {
                streamError = new Error(event.error?.message ?? 'Investigation failed');
              }
            }
          } catch {
            // ignore malformed frame
          }
        }
      }
      boundary = buffer.indexOf('\n\n');
    }
  }

  if (streamError) {
    throw streamError;
  }
  if (!finalResult) {
    throw new Error('Investigation stream ended without a result');
  }
  return {
    ...finalResult,
    evidence: finalResult.evidence ?? [],
    answer: finalResult.answer ?? null,
  };
}

export interface IndexJobResponse {
  jobId: string;
  service: string;
  status: string;
  error?: string;
  stats?: Record<string, unknown>;
}

async function pollJob(
  fetchJob: (jobId: string) => Promise<IndexJobResponse>,
  jobId: string,
  options?: {
    intervalMs?: number;
    timeoutMs?: number;
    onProgress?: (job: IndexJobResponse) => void;
    timeoutMessage?: string;
  },
): Promise<IndexJobResponse> {
  const intervalMs = options?.intervalMs ?? 1500;
  const timeoutMs = options?.timeoutMs ?? 300_000;
  const started = Date.now();
  let backoffMs = intervalMs;

  for (;;) {
    try {
      const job = await fetchJob(jobId);
      backoffMs = intervalMs;
      options?.onProgress?.(job);
      if (job.status === 'completed' || job.status === 'failed') {
        return job;
      }
    } catch (error) {
      if (!isRetryableJobPollError(error) || Date.now() - started > timeoutMs) {
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, backoffMs));
      backoffMs = Math.min(backoffMs * 2, 10_000);
      continue;
    }
    if (Date.now() - started > timeoutMs) {
      throw new Error(options?.timeoutMessage ?? 'Indexing timed out — check the job status later');
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

export async function startCodeIndex(input: {
  service?: string;
  localPath: string;
  embed?: boolean;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      localPath: input.localPath,
      embed: input.embed ?? false,
    }),
  });
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Code index failed (${response.status})`);
  }
  return body;
}

export async function getCodeIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Index job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollCodeIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<IndexJobResponse> {
  return pollJob(getCodeIndexJob, jobId, options);
}

export async function startHistoryIndex(input: {
  service?: string;
  localPath: string;
  embed?: boolean;
  maxCommits?: number;
  since?: string;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/history`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      localPath: input.localPath,
      embed: input.embed ?? false,
      maxCommits: input.maxCommits,
      since: input.since,
    }),
  });
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `History index failed (${response.status})`);
  }
  return body;
}

export async function getHistoryIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/history/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `History job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollHistoryIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<IndexJobResponse> {
  return pollJob(getHistoryIndexJob, jobId, options);
}

export async function startNotionIndex(input: {
  service?: string;
  apiKey: string;
  pageIds?: string;
  databaseIds?: string;
  embed?: boolean;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/notion`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      apiKey: input.apiKey,
      pageIds: input.pageIds ?? '',
      databaseIds: input.databaseIds ?? '',
      embed: input.embed ?? false,
    }),
  });
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Notion index failed (${response.status})`);
  }
  return body;
}

export async function getNotionIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/notion/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Notion job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollNotionIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<IndexJobResponse> {
  return pollJob(getNotionIndexJob, jobId, options);
}

export interface JiraTestResponse {
  ok: boolean;
  displayName?: string;
  email?: string;
  accessibleProjectKeys?: string[];
  missingProjectKeys?: string[];
  error?: string;
}

export async function testJiraConnection(input: {
  projectKeys?: string;
}): Promise<JiraTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/jira/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      projectKeys: input.projectKeys ?? '',
    }),
  });
  const body = await readJson<JiraTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Jira connection test failed (${response.status})`);
  }
  return body;
}

export async function startJiraIndex(input: {
  service?: string;
  projectKeys?: string;
  lookbackDays?: number;
  embed?: boolean;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/jira`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      projectKeys: input.projectKeys ?? '',
      lookbackDays: input.lookbackDays ?? 90,
      embed: input.embed ?? false,
    }),
  });
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Jira index failed (${response.status})`);
  }
  return body;
}

export async function getJiraIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/jira/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Jira job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollJiraIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<IndexJobResponse> {
  return pollJob(getJiraIndexJob, jobId, options);
}

export interface LinearTestResponse {
  ok: boolean;
  name?: string;
  userId?: string;
  accessibleTeamKeys?: string[];
  missingTeamKeys?: string[];
  error?: string;
}

export async function testLinearConnection(input: {
  teamKeys?: string;
}): Promise<LinearTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/linear/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      teamKeys: input.teamKeys ?? '',
    }),
  });
  const body = await readJson<LinearTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Linear connection test failed (${response.status})`);
  }
  return body;
}

export async function startLinearIndex(input: {
  service?: string;
  teamKeys?: string;
  lookbackDays?: number;
  embed?: boolean;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/linear`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      teamKeys: input.teamKeys ?? '',
      lookbackDays: input.lookbackDays ?? 90,
      embed: input.embed ?? false,
    }),
  });
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Linear index failed (${response.status})`);
  }
  return body;
}

export async function getLinearIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/linear/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Linear job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollLinearIndexJob(
  jobId: string,
  options?: { intervalMs?: number; timeoutMs?: number },
): Promise<IndexJobResponse> {
  return pollJob(getLinearIndexJob, jobId, options);
}

export interface ZohoTestResponse {
  ok: boolean;
  region?: string;
  moduleCount?: number;
  hasDealsModule?: boolean;
  error?: string;
  code?: string;
  connectionId?: string;
}

export async function testZohoConnection(input: {
  service: string;
  connectionId: string;
}): Promise<ZohoTestResponse> {
  const response = await andromediaFetch(`/api/v1/integrations/zoho/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: input.service,
      connectionId: input.connectionId,
    }),
  });
  const body = await readJson<ZohoTestResponse>(response);
  if (!response.ok || body.ok === false) {
    throwHttpError(
      body,
      response.status,
      body.error ?? `Zoho connection test failed (${response.status})`,
    );
  }
  return body;
}

export async function startZohoIndex(input: {
  service?: string;
  connectionId: string;
  lookbackDays?: number;
  minQualityScore?: number;
  embed?: boolean;
  fullRefresh?: boolean;
}): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/zoho`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      connectionId: input.connectionId,
      lookbackDays: input.lookbackDays ?? 90,
      minQualityScore: input.minQualityScore ?? 0.35,
      embed: input.embed ?? false,
      fullRefresh: input.fullRefresh ?? false,
    }),
  });
  const body = await readJson<IndexJobResponse & { code?: string; error?: string }>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Zoho index failed (${response.status})`);
  }
  return body;
}

export async function getZohoIndexJob(jobId: string): Promise<IndexJobResponse> {
  const response = await andromediaFetch(`/api/v1/index/zoho/${encodeURIComponent(jobId)}`);
  const body = await readJson<IndexJobResponse>(response);
  if (!response.ok) {
    throwHttpError(body, response.status, `Zoho job lookup failed (${response.status})`);
  }
  return body;
}

export async function pollZohoIndexJob(
  jobId: string,
  options?: {
    intervalMs?: number;
    timeoutMs?: number;
    onProgress?: (job: IndexJobResponse) => void;
  },
): Promise<IndexJobResponse> {
  return pollJob(getZohoIndexJob, jobId, {
    ...options,
    timeoutMessage: 'Zoho indexing timed out — check the job status later',
  });
}

export function formatIndexSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const digits = value >= 10 || unitIndex === 0 ? 0 : 1;
  return `${value.toFixed(digits)} ${units[unitIndex]}`;
}

export function formatRelativeSync(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) {
    return 'never';
  }
  const then = Date.parse(iso);
  if (Number.isNaN(then)) {
    return 'never';
  }
  const deltaSec = Math.max(0, Math.floor((now - then) / 1000));
  if (deltaSec < 60) {
    return 'just now';
  }
  const minutes = Math.floor(deltaSec / 60);
  if (minutes < 60) {
    return minutes === 1 ? '1 min ago' : `${minutes} min ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 48) {
    return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  }
  const days = Math.floor(hours / 24);
  return days === 1 ? '1 day ago' : `${days} days ago`;
}

/* ---- Code / source search ---- */

/**
 * A single search hit. The API returns these fields for every source type;
 * the Zoho-specific ones are present only for CRM hits, so everything beyond
 * path/fileName/sourceType is optional.
 */
export interface CodeSearchHit {
  path: string;
  filePath: string;
  fileName: string;
  score: number;
  chunkId: string;
  sourceType: string | null;
  commitSha?: string | null;
  timestamp?: string | null;
  content?: string | null;
  accountName?: string | null;
  stage?: string | null;
  amount?: string | null;
  probability?: string | null;
  closingDate?: string | null;
  nextStep?: string | null;
}

export interface CodeSearchResponse {
  service: string;
  indexPath: string;
  indexPaths: string[];
  hits: CodeSearchHit[];
  totalHits: number;
  elapsedMs: number | null;
}

/** Source types that describe code and history rather than chat/CRM records. */
export const CODE_SOURCE_TYPES = ['CODE', 'GIT'] as const;

export async function searchSources(input: {
  query: string;
  service?: string;
  topK?: number;
  hybrid?: boolean;
  sourceTypes?: string[];
}): Promise<CodeSearchResponse> {
  const response = await andromediaFetch('/api/v1/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      query: input.query,
      topK: input.topK,
      hybrid: input.hybrid,
      sourceTypes: input.sourceTypes,
    }),
  });
  const body = await readJson<CodeSearchResponse & { error?: string }>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Search failed (${response.status})`);
  }
  return body;
}

/** Group hits by source type for compact "N results across M sources" summaries. */
export function countBySourceType(hits: CodeSearchHit[]): Record<string, number> {
  return hits.reduce<Record<string, number>>((acc, hit) => {
    const key = (hit.sourceType ?? 'UNKNOWN').toUpperCase();
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {});
}

/** Short, human label for a source type. */
export function sourceTypeLabel(sourceType: string | null | undefined): string {
  switch ((sourceType ?? '').toUpperCase()) {
    case 'CODE':
      return 'Code';
    case 'GIT':
      return 'Git history';
    case 'SLACK':
      return 'Slack';
    case 'NOTION':
      return 'Notion';
    case 'JIRA':
      return 'Jira';
    case 'LINEAR':
      return 'Linear';
    case 'GOOGLE_CHAT':
      return 'Google Chat';
    case 'ZOHO':
      return 'Zoho CRM';
    default:
      return sourceType || 'Unknown';
  }
}

/** A synthesized answer over retrieved hits, plus the sources it used. */
export interface SearchAnswerResponse {
  service: string;
  query: string;
  answer: string;
  grounded: boolean;
  citations: Array<{ label: string; path?: string | null; sourceType?: string | null }>;
  hits: CodeSearchHit[];
  totalHits: number;
}

/**
 * Retrieval plus a readable answer.
 *
 * Separate from `searchSources` because this spends a model call: it consumes
 * trial quota and fails softly when no provider key is configured, whereas
 * `searchSources` always works. The raw hits come back too, so the UI can show
 * the evidence behind the answer.
 */
export async function searchAnswer(input: {
  query: string;
  service?: string;
  topK?: number;
  sourceTypes?: string[];
}): Promise<SearchAnswerResponse> {
  const response = await andromediaFetch('/api/v1/search/answer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: resolveService(input.service),
      query: input.query,
      topK: input.topK,
      sourceTypes: input.sourceTypes,
    }),
  });
  const body = await readJson<
    SearchAnswerResponse & {
      error?: string;
      code?: string;
      queriesUsed?: number;
      maxQueries?: number;
    }
  >(response);
  if (response.status === 429 && body.code === 'TRIAL_LIMIT') {
    throw new TrialLimitError(
      body.error ?? 'Trial question limit reached',
      body.queriesUsed ?? 0,
      body.maxQueries ?? 0,
    );
  }
  if (!response.ok) {
    throw new Error(body.error ?? `Answer failed (${response.status})`);
  }
  return body;
}
