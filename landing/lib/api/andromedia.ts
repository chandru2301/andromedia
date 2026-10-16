const DEFAULT_API_BASE = 'http://localhost:8083';

export function andromediaApiBase(): string {
  const fromEnv = process.env.NEXT_PUBLIC_ANDROMEDIA_API_URL;
  if (fromEnv && fromEnv.trim()) {
    return fromEnv.replace(/\/$/, '');
  }
  return DEFAULT_API_BASE;
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

async function readJson<T>(response: Response): Promise<T & { error?: string; code?: string }> {
  return (await response.json().catch(() => ({}))) as T & { error?: string; code?: string };
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

function trialAuthHeaders(input?: {
  trialId?: string | null;
  accessToken?: string | null;
}): Record<string, string> {
  const headers: Record<string, string> = {};
  if (input?.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input?.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  return headers;
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
  trialMode?: boolean;
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

  constructor(
    message: string,
    readonly queriesUsed: number,
    readonly maxQueries: number,
  ) {
    super(message);
    this.name = 'TrialLimitError';
  }
}

export async function fetchTrialConfig(): Promise<TrialConfig> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/trial/config`);
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
  const response = await fetch(`${andromediaApiBase()}/api/v1/trial/start`, {
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
    `${andromediaApiBase()}/api/v1/trial/status/${encodeURIComponent(trialId)}`,
    Object.keys(headers).length > 0 ? { headers } : undefined,
  );
  const body = await readJson<TrialStatus>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Trial status failed (${response.status})`);
  }
  return body;
}

export interface ChatProviderUpdate {
  provider: ChatProviderId;
  model?: string;
  apiKey?: string;
}

export async function getChatProviderSelection(input?: {
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<ChatProviderSelection> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/settings/chat-provider`, {
    headers: trialAuthHeaders(input),
  });
  const body = await readJson<ChatProviderSelection>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Chat provider lookup failed (${response.status})`);
  }
  return body;
}

export async function updateChatProvider(
  input: ChatProviderId | ChatProviderUpdate,
  auth?: { trialId?: string | null; accessToken?: string | null },
): Promise<ChatProviderSelection> {
  const payload: ChatProviderUpdate =
    typeof input === 'string' ? { provider: input } : input;
  const response = await fetch(`${andromediaApiBase()}/api/v1/settings/chat-provider`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(auth),
    },
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
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<SlackIndexJobResponse> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...trialAuthHeaders({
      trialId: input.trialId ?? input.service,
      accessToken: input.accessToken,
    }),
  };
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/slack`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      service: input.service ?? 'local',
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
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/slack/${jobId}`);
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
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<SlackTestResponse> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...trialAuthHeaders(input),
  };
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/slack/test`, {
    method: 'POST',
    headers,
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

export interface SlackChannelOption {
  id: string;
  name: string;
  isMember?: boolean;
}

export async function listSlackChannels(input: {
  botToken: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<SlackChannelOption[]> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...trialAuthHeaders(input),
  };
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/slack/channels`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ botToken: input.botToken }),
  });
  const body = await readJson<{ ok?: boolean; channels?: SlackChannelOption[] }>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Slack channel list failed (${response.status})`);
  }
  return body.channels ?? [];
}

export async function sendSlackMessage(input: {
  service?: string;
  trialId?: string;
  accessToken?: string | null;
  botToken: string;
  channelId: string;
  title?: string;
  summary?: string;
  reasons?: string[];
  primaryFields?: Record<string, string>;
  flags?: string[];
  link?: string;
}): Promise<{ ok: boolean; channel?: string; ts?: string }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/slack/message`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      service: input.service,
      botToken: input.botToken,
      channelId: input.channelId,
      title: input.title,
      summary: input.summary,
      reasons: input.reasons,
      primaryFields: input.primaryFields,
      flags: input.flags,
      link: input.link,
    }),
  });
  const body = await readJson<{ ok?: boolean; channel?: string; ts?: string }>(response);
  if (!response.ok || body.ok === false) {
    throw new Error(body.error ?? `Slack send failed (${response.status})`);
  }
  return { ok: true, channel: body.channel, ts: body.ts };
}

export interface SlackAlertConfigResponse {
  service: string;
  enabled: boolean;
  hasBotToken: boolean;
  channelId: string;
  channelName: string;
  configured: boolean;
  error?: string;
}

export async function getSlackAlertConfig(input: {
  service: string;
  trialId?: string;
  accessToken?: string | null;
}): Promise<SlackAlertConfigResponse> {
  const headers: Record<string, string> = {};
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/alerts/slack?service=${encodeURIComponent(input.service)}`,
    { headers },
  );
  const body = await readJson<SlackAlertConfigResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Alert config lookup failed (${response.status})`);
  }
  return body;
}

export async function putSlackAlertConfig(input: {
  service: string;
  trialId?: string;
  accessToken?: string | null;
  enabled: boolean;
  botToken?: string;
  channelId: string;
  channelName?: string;
}): Promise<SlackAlertConfigResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const response = await fetch(`${andromediaApiBase()}/api/v1/alerts/slack`, {
    method: 'PUT',
    headers,
    body: JSON.stringify({
      service: input.service,
      enabled: input.enabled,
      botToken: input.botToken,
      channelId: input.channelId,
      channelName: input.channelName,
    }),
  });
  const body = await readJson<SlackAlertConfigResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Alert config save failed (${response.status})`);
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
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<NotionTestResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/notion/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(input),
    },
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
    const response = await fetch(`${andromediaApiBase()}/api/v1/health`);
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

export async function fetchIndexOverview(
  service = 'local',
  trialId?: string,
  accessToken?: string | null,
): Promise<IndexOverviewResponse> {
  const headers: Record<string, string> = {};
  if (trialId) {
    headers['X-Trial-Id'] = trialId;
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/services/${encodeURIComponent(service)}/overview`,
    Object.keys(headers).length > 0 ? { headers } : undefined,
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
    namedEntityPin?: boolean;
    canonicalEntityKey?: string;
  };
  evidence: InvestigationEvidence[];
  answer?: InvestigationAnswer | null;
}

export async function investigateCrm(input: {
  query: string;
  service?: string;
  trialId?: string;
  accessToken?: string | null;
  conversationBag?: Record<string, unknown> | null;
}): Promise<InvestigationResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const context: Record<string, unknown> = {
    trustMode: 'structured-evidence',
  };
  if (input.conversationBag && Object.keys(input.conversationBag).length > 0) {
    context.conversationBag = input.conversationBag;
  }
  const response = await fetch(`${andromediaApiBase()}/api/v1/investigate`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      service: input.service ?? 'local',
      query: input.query,
      context,
    }),
  });
  const body = await readJson<
    InvestigationResponse & {
      code?: string;
      queriesUsed?: number;
      maxQueries?: number;
    }
  >(response);
  if (response.status === 429 && body.code === 'TRIAL_LIMIT') {
    throw new TrialLimitError(
      body.error ?? 'Trial question limit reached',
      body.queriesUsed ?? 0,
      body.maxQueries ?? 20,
    );
  }
  if (!response.ok) {
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
 * Streaming twin of {@link investigateCrm}: same request shape, but resolves once the terminal
 * `result` event arrives while emitting every real progress event to `onEvent` as it happens —
 * no client-side fake timing.
 */
export async function investigateCrmStream(input: {
  query: string;
  service?: string;
  trialId?: string;
  accessToken?: string | null;
  conversationBag?: Record<string, unknown> | null;
  onEvent?: (event: InvestigationProgressEvent) => void;
}): Promise<InvestigationResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const context: Record<string, unknown> = {
    trustMode: 'structured-evidence',
  };
  if (input.conversationBag && Object.keys(input.conversationBag).length > 0) {
    context.conversationBag = input.conversationBag;
  }
  const response = await fetch(`${andromediaApiBase()}/api/v1/investigate/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      service: input.service ?? 'local',
      query: input.query,
      context,
    }),
  });

  const contentType = response.headers.get('content-type') ?? '';
  if (!response.ok || !contentType.includes('text/event-stream') || !response.body) {
    const body = await readJson<
      InvestigationResponse & { code?: string; queriesUsed?: number; maxQueries?: number }
    >(response);
    if (response.status === 429 && body.code === 'TRIAL_LIMIT') {
      throw new TrialLimitError(
        body.error ?? 'Trial question limit reached',
        body.queriesUsed ?? 0,
        body.maxQueries ?? 20,
      );
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
              streamError = new Error(event.error?.message ?? 'Investigation failed');
            }
          } catch {
            // ignore a malformed frame rather than failing the whole stream
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
  const response = await fetch(`${andromediaApiBase()}/api/v1/index`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: input.service ?? 'local',
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
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/${encodeURIComponent(jobId)}`);
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
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/history`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: input.service ?? 'local',
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
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/history/${encodeURIComponent(jobId)}`,
  );
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
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<IndexJobResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/notion`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders({
        trialId: input.trialId ?? input.service,
        accessToken: input.accessToken,
      }),
    },
    body: JSON.stringify({
      service: input.service ?? 'local',
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
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/notion/${encodeURIComponent(jobId)}`,
  );
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
  baseUrl: string;
  email: string;
  apiToken: string;
  projectKeys?: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<JiraTestResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/jira/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(input),
    },
    body: JSON.stringify({
      baseUrl: input.baseUrl,
      email: input.email,
      apiToken: input.apiToken,
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
  baseUrl: string;
  email: string;
  apiToken: string;
  projectKeys?: string;
  lookbackDays?: number;
  embed?: boolean;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<IndexJobResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/jira`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders({
        trialId: input.trialId ?? input.service,
        accessToken: input.accessToken,
      }),
    },
    body: JSON.stringify({
      service: input.service ?? 'local',
      baseUrl: input.baseUrl,
      email: input.email,
      apiToken: input.apiToken,
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
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/jira/${encodeURIComponent(jobId)}`,
  );
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
  apiKey: string;
  teamKeys?: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<LinearTestResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/linear/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(input),
    },
    body: JSON.stringify({
      apiKey: input.apiKey,
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
  apiKey: string;
  teamKeys?: string;
  lookbackDays?: number;
  embed?: boolean;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<IndexJobResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/linear`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders({
        trialId: input.trialId ?? input.service,
        accessToken: input.accessToken,
      }),
    },
    body: JSON.stringify({
      service: input.service ?? 'local',
      apiKey: input.apiKey,
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
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/linear/${encodeURIComponent(jobId)}`,
  );
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

export interface ZohoGrantExchangeResponse {
  ok: boolean;
  connectionId?: string;
  connectionKey?: string;
  region?: string;
  error?: string;
  code?: string;
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

export async function exchangeZohoGrant(input: {
  service: string;
  clientId: string;
  clientSecret: string;
  grantToken: string;
  region: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<ZohoGrantExchangeResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/zoho/exchange-grant`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(input),
    },
    body: JSON.stringify({
      service: input.service,
      clientId: input.clientId,
      clientSecret: input.clientSecret,
      grantToken: input.grantToken,
      region: input.region,
    }),
  });
  const body = await readJson<ZohoGrantExchangeResponse>(response);
  if (!response.ok || body.ok === false) {
    throwHttpError(
      body,
      response.status,
      body.error ?? `Zoho grant exchange failed (${response.status})`,
    );
  }
  return body;
}

export async function testZohoConnection(input: {
  service: string;
  connectionId: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<ZohoTestResponse> {
  const response = await fetch(`${andromediaApiBase()}/api/v1/integrations/zoho/test`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...trialAuthHeaders(input),
    },
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
  trialId?: string;
  accessToken?: string | null;
  connectionId: string;
  lookbackDays?: number;
  minQualityScore?: number;
  embed?: boolean;
  fullRefresh?: boolean;
}): Promise<IndexJobResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (input.trialId) {
    headers['X-Trial-Id'] = input.trialId;
  }
  if (input.accessToken) {
    headers.Authorization = `Bearer ${input.accessToken}`;
  }
  const response = await fetch(`${andromediaApiBase()}/api/v1/index/zoho`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      service: input.service ?? 'local',
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
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/index/zoho/${encodeURIComponent(jobId)}`,
  );
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

export type IndexFreshness = 'fresh' | 'aging' | 'stale' | 'unknown';

const FRESH_MS = 60 * 60 * 1000;
const AGING_MS = 24 * 60 * 60 * 1000;

export function deriveIndexFreshness(
  iso: string | null | undefined,
  now = Date.now(),
): IndexFreshness {
  if (!iso) {
    return 'unknown';
  }
  const then = Date.parse(iso);
  if (Number.isNaN(then)) {
    return 'unknown';
  }
  const ageMs = Math.max(0, now - then);
  if (ageMs < FRESH_MS) {
    return 'fresh';
  }
  if (ageMs < AGING_MS) {
    return 'aging';
  }
  return 'stale';
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

export interface StoredCredentialView {
  provider: string;
  hasSecret: boolean;
  status: string;
  metadata: Record<string, unknown>;
  updatedAt?: string;
}

export interface CredentialsListResponse {
  enabled: boolean;
  credentials: StoredCredentialView[];
}

export async function fetchUserCredentials(input: {
  service: string;
  trialId?: string | null;
  accessToken?: string | null;
}): Promise<CredentialsListResponse> {
  const headers = trialAuthHeaders({
    trialId: input.trialId ?? input.service,
    accessToken: input.accessToken,
  });
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/credentials?service=${encodeURIComponent(input.service)}`,
    { headers },
  );
  const body = await readJson<CredentialsListResponse>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Credential list failed (${response.status})`);
  }
  return {
    enabled: Boolean(body.enabled),
    credentials: body.credentials ?? [],
  };
}

export async function putUserCredential(input: {
  provider: string;
  service: string;
  trialId?: string | null;
  accessToken?: string | null;
  secrets?: Record<string, string>;
  metadata?: Record<string, unknown>;
}): Promise<StoredCredentialView> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...trialAuthHeaders({
      trialId: input.trialId ?? input.service,
      accessToken: input.accessToken,
    }),
  };
  const response = await fetch(
    `${andromediaApiBase()}/api/v1/credentials/${encodeURIComponent(input.provider)}`,
    {
      method: 'PUT',
      headers,
      body: JSON.stringify({
        secrets: input.secrets ?? {},
        metadata: input.metadata ?? {},
      }),
    },
  );
  const body = await readJson<StoredCredentialView>(response);
  if (!response.ok) {
    throw new Error(body.error ?? `Credential save failed (${response.status})`);
  }
  return body;
}
