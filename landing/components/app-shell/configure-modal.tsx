"use client";

import { useState, useEffect } from 'react';
import { IntegrationIcon } from '@/components/app-shell/integration-icon';
import { useAndroConfig } from '@/hooks/use-andro-config';
import { useIndexOverview } from '@/hooks/use-index-overview';
import { useTrial } from '@/hooks/use-trial';
import { PasswordInput } from '@/components/app-shell/password-input';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogCloseButton,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
  AndromediaApiError,
  checkAndromediaHealth,
  exchangeZohoGrant,
  getSlackAlertConfig,
  listSlackChannels,
  pollCodeIndexJob,
  pollHistoryIndexJob,
  pollJiraIndexJob,
  pollLinearIndexJob,
  pollNotionIndexJob,
  pollSlackIndexJob,
  pollZohoIndexJob,
  putSlackAlertConfig,
  startCodeIndex,
  startHistoryIndex,
  startJiraIndex,
  startLinearIndex,
  startNotionIndex,
  startSlackIndex,
  startZohoIndex,
  testJiraConnection,
  testLinearConnection,
  testNotionConnection,
  testSlackConnection,
  testZohoConnection,
  isNoNewSlackThreads,
  type SlackChannelOption,
} from '@/lib/api/andromedia';
import type {
  GitCredentials,
  IntegrationConfig,
  IntegrationId,
  JiraCredentials,
  LinearCredentials,
  NotionCredentials,
  SlackCredentials,
  TestStatus,
  ZohoCredentials,
  ZohoRegion,
} from '@/types/product-config';
import { isSupportedIntegration } from '@/types/product-config';
import { useToast } from '@/components/ui/use-toast';

interface ConfigureModalProps {
  integrationId: IntegrationId | null;
  onClose: () => void;
}

function FieldRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <Label className="text-xs text-muted-foreground">{label}</Label>
      {children}
    </div>
  );
}

function SectionHeading({ title, step }: { title: string; step: number }) {
  return (
    <div className="flex items-center gap-2 pt-2">
      <span className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[10px] font-medium text-muted-foreground">
        {step}
      </span>
      <h3 className="text-sm font-medium">{title}</h3>
    </div>
  );
}

function ZohoConfigureFields({
  credentials,
  onUpdate,
  onExchangeGrant,
  exchangingGrant,
  testStatus,
  onTest,
  saving,
  indexJobStatus,
  fullRefresh,
  onFullRefreshChange,
}: {
  credentials: ZohoCredentials;
  onUpdate: (patch: Partial<ZohoCredentials>) => void;
  onExchangeGrant: () => void;
  exchangingGrant: boolean;
  testStatus: TestStatus;
  onTest: () => void;
  saving: boolean;
  indexJobStatus: string | null;
  fullRefresh: boolean;
  onFullRefreshChange: (value: boolean) => void;
}) {
  const hasConnection = Boolean(credentials.connectionId?.trim());

  return (
    <div className="space-y-5">
      <p className="text-xs text-muted-foreground leading-relaxed">
        Use a Zoho Self Client from the{' '}
        <a
          href="https://api-console.zoho.com/"
          target="_blank"
          rel="noreferrer"
          className="text-primary underline-offset-2 hover:underline"
        >
          Zoho API Console
        </a>
        . Generate a one-time grant token with CRM scopes, exchange it here, then test and index.
        After exchange, the refresh token stays on the Andromedia server (encrypted). This browser
        only keeps a connection id — never the refresh token.
      </p>

      <div className="space-y-3 rounded-lg border border-border p-4">
        <SectionHeading title="Connect" step={1} />
        <FieldRow label="Region">
          <select
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            value={credentials.region}
            onChange={(e) => onUpdate({ region: e.target.value as ZohoRegion })}
          >
            <option value="com">US / Global (zoho.com)</option>
            <option value="in">India (zoho.in)</option>
          </select>
        </FieldRow>
        <FieldRow label="Self Client ID">
          <Input
            value={credentials.clientId}
            placeholder="1000.XXXXXXXXXXXX"
            onChange={(e) => onUpdate({ clientId: e.target.value })}
          />
        </FieldRow>
        <FieldRow label="Self Client secret">
          <PasswordInput
            value={credentials.clientSecret}
            onChange={(e) => onUpdate({ clientSecret: e.target.value })}
          />
        </FieldRow>
        <FieldRow label="One-time grant token">
          <PasswordInput
            value={credentials.grantToken}
            placeholder="Paste grant token from API Console"
            onChange={(e) => onUpdate({ grantToken: e.target.value })}
          />
        </FieldRow>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onExchangeGrant}
          disabled={exchangingGrant || saving}
        >
          {exchangingGrant ? 'exchanging…' : 'exchange grant token'}
        </Button>
      </div>

      <div className="space-y-3 rounded-lg border border-border p-4">
        <SectionHeading title="Verify" step={2} />
        <p className="text-xs text-muted-foreground">
          {hasConnection
            ? `Connected (server connection ${credentials.connectionId.slice(0, 8)}…).`
            : 'Exchange a grant token first so the server can vault the refresh token.'}
        </p>
        <div className="flex items-center gap-3">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onTest}
            disabled={!hasConnection || testStatus === 'checking' || saving || exchangingGrant}
          >
            {testStatus === 'checking' ? 'testing…' : 'test connection'}
          </Button>
          <TestStatusBadge status={testStatus} />
        </div>
      </div>

      <div className="space-y-3 rounded-lg border border-border p-4">
        <SectionHeading title="Index" step={3} />
        <FieldRow label="Lookback days">
          <Input
            type="number"
            value={credentials.lookbackDays ?? 90}
            onChange={(e) => onUpdate({ lookbackDays: Number(e.target.value) })}
          />
        </FieldRow>
        <FieldRow label="Minimum quality score (0–1)">
          <Input
            type="number"
            step="0.05"
            min="0"
            max="1"
            value={credentials.minQualityScore ?? 0.35}
            onChange={(e) => onUpdate({ minQualityScore: Number(e.target.value) })}
          />
        </FieldRow>
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <Label className="text-xs text-muted-foreground">
              Full refresh (replace CRM snapshot)
            </Label>
            <p className="text-[11px] leading-4 text-muted-foreground">
              Incremental keeps prior Zoho chunks unless the connection fingerprint changed. Full
              refresh wipes ZOHO docs in the active Lucene bucket, then rewrites them.
            </p>
          </div>
          <Switch checked={fullRefresh} onCheckedChange={onFullRefreshChange} />
        </div>
        <div className="flex items-center justify-between">
          <Label className="text-xs text-muted-foreground">Generate embeddings during index</Label>
          <Switch
            checked={credentials.embed ?? false}
            onCheckedChange={(embed) => onUpdate({ embed })}
          />
        </div>
        {saving && indexJobStatus ? (
          <p className="text-xs text-muted-foreground">Indexing job: {indexJobStatus}</p>
        ) : null}
      </div>
    </div>
  );
}

function CredentialFields({
  integration,
  onUpdate,
}: {
  integration: IntegrationConfig;
  onUpdate: (patch: Partial<IntegrationConfig['credentials']>) => void;
}) {
  const creds = integration.credentials;

  switch (integration.id) {
    case 'git': {
      const c = creds as GitCredentials;
      return (
        <>
          <FieldRow label="Repo path (absolute path on the Andromedia host)">
            <Input
              value={c.repoPath}
              placeholder="/home/you/my-service"
              onChange={(e) => onUpdate({ repoPath: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Branch">
            <Input value={c.branch} onChange={(e) => onUpdate({ branch: e.target.value })} />
          </FieldRow>
          <div className="flex items-center justify-between">
            <Label className="text-xs text-muted-foreground">Index commit history</Label>
            <Switch
              checked={c.commitHistory}
              onCheckedChange={(commitHistory) => onUpdate({ commitHistory })}
            />
          </div>
          {c.commitHistory && (
            <FieldRow label="Max commit history days">
              <Input
                type="number"
                value={c.maxCommitHistoryDays}
                onChange={(e) => onUpdate({ maxCommitHistoryDays: Number(e.target.value) })}
              />
            </FieldRow>
          )}
        </>
      );
    }
    case 'slack': {
      const c = creds as SlackCredentials;
      return (
        <>
          <p className="text-xs leading-relaxed text-muted-foreground">
            Bot token needs history scopes for indexing, plus{' '}
            <code className="text-[11px]">chat:write</code> for Send to Slack and pipeline alerts.
            Invite the bot to every channel you index or notify.
          </p>
          <FieldRow label="Bot token">
            <PasswordInput
              value={c.botToken}
              placeholder={
                integration.hasSavedSecret ? 'Saved on server — leave blank to keep' : ''
              }
              onChange={(e) => onUpdate({ botToken: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Channels">
            <Input
              value={c.channels}
              placeholder="#all-causr"
              onChange={(e) => onUpdate({ channels: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Lookback days">
            <Input
              type="number"
              value={c.lookbackDays}
              onChange={(e) => onUpdate({ lookbackDays: Number(e.target.value) })}
            />
          </FieldRow>
        </>
      );
    }
    case 'notion': {
      const c = creds as NotionCredentials;
      return (
        <>
          <FieldRow label="Integration token (secret_…)">
            <PasswordInput
              value={c.apiKey}
              onChange={(e) => onUpdate({ apiKey: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Page IDs (comma-separated)">
            <Input
              value={c.pageIds}
              placeholder="abc123, def456"
              onChange={(e) => onUpdate({ pageIds: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Database IDs (optional, comma-separated)">
            <Input
              value={c.databaseIds}
              placeholder="db-uuid"
              onChange={(e) => onUpdate({ databaseIds: e.target.value })}
            />
          </FieldRow>
        </>
      );
    }
    case 'jira': {
      const c = creds as JiraCredentials;
      return (
        <>
          <FieldRow label="Base URL">
            <Input
              value={c.baseUrl}
              placeholder="https://yourname.atlassian.net"
              onChange={(e) => onUpdate({ baseUrl: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Email">
            <Input
              value={c.email}
              placeholder="you@company.com"
              onChange={(e) => onUpdate({ email: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="API token">
            <PasswordInput
              value={c.apiToken}
              onChange={(e) => onUpdate({ apiToken: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Project keys (comma-separated)">
            <Input
              value={c.projectKeys}
              placeholder="AUTH, ENG"
              onChange={(e) => onUpdate({ projectKeys: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Lookback days">
            <Input
              type="number"
              value={c.lookbackDays ?? 90}
              onChange={(e) => onUpdate({ lookbackDays: Number(e.target.value) })}
            />
          </FieldRow>
        </>
      );
    }
    case 'linear': {
      const c = creds as LinearCredentials;
      return (
        <>
          <FieldRow label="API key (lin_api_…)">
            <PasswordInput
              value={c.apiKey}
              onChange={(e) => onUpdate({ apiKey: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Team keys (comma-separated)">
            <Input
              value={c.teamKeys}
              placeholder="ENG"
              onChange={(e) => onUpdate({ teamKeys: e.target.value })}
            />
          </FieldRow>
          <FieldRow label="Lookback days">
            <Input
              type="number"
              value={c.lookbackDays ?? 90}
              onChange={(e) => onUpdate({ lookbackDays: Number(e.target.value) })}
            />
          </FieldRow>
        </>
      );
    }
    default:
      return (
        <p className="text-sm text-muted-foreground">
          This integration is not available yet. Only Git, Slack, Notion, Jira, Linear, and Zoho are
          wired to the Andromedia backend.
        </p>
      );
  }
}

function TestStatusBadge({ status }: { status: TestStatus }) {
  if (status === 'checking') return <Badge variant="outline">checking...</Badge>;
  if (status === 'connected') return <Badge>LIVE</Badge>;
  if (status === 'failed') return <Badge variant="destructive">failed — check credentials</Badge>;
  return null;
}

function sinceIsoFromDays(days: number): string {
  const safeDays = Number.isFinite(days) && days > 0 ? days : 90;
  return new Date(Date.now() - safeDays * 24 * 60 * 60 * 1000).toISOString();
}

function errorCode(error: unknown): string | undefined {
  if (error instanceof AndromediaApiError) {
    return error.code;
  }
  if (error && typeof error === 'object' && 'code' in error) {
    const code = (error as { code?: unknown }).code;
    return typeof code === 'string' ? code : undefined;
  }
  return undefined;
}

function isZohoReauthRequired(error: unknown): boolean {
  return errorCode(error) === 'ZOHO_REAUTH_REQUIRED';
}

function isTransientIndexStatusError(error: unknown): boolean {
  if (error instanceof AndromediaApiError) {
    return error.status === 429 || error.status === 503;
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase();
    return (
      msg.includes('rate limit') ||
      msg.includes('timed out') ||
      msg.includes('failed to fetch') ||
      msg.includes('job lookup failed')
    );
  }
  return false;
}

export function ConfigureModal({ integrationId, onClose }: ConfigureModalProps) {
  const {
    integrations,
    updateIntegrationCredentials,
    setIntegrationTestStatus,
    setIntegrationStatus,
    markSavedSecret,
    save,
  } = useAndroConfig();
  const { serviceName, isTrial, trialId, getAccessToken } = useTrial();
  const overview = useIndexOverview(
    serviceName,
    isTrial && trialId ? trialId : undefined,
    isTrial ? getAccessToken : undefined,
  );
  const { toast } = useToast();
  const [testStatus, setTestStatus] = useState<TestStatus>('idle');
  const [saving, setSaving] = useState(false);
  const [exchangingGrant, setExchangingGrant] = useState(false);
  const [indexJobStatus, setIndexJobStatus] = useState<string | null>(null);
  const [fullRefresh, setFullRefresh] = useState(false);
  const [alertsEnabled, setAlertsEnabled] = useState(false);
  const [alertChannels, setAlertChannels] = useState<SlackChannelOption[]>([]);
  const [alertChannelId, setAlertChannelId] = useState('');
  const [alertChannelsLoading, setAlertChannelsLoading] = useState(false);
  const [alertsSaving, setAlertsSaving] = useState(false);

  const integration = integrations.find((i) => i.id === integrationId) ?? null;
  const open = integrationId !== null && integration !== null;
  const supported = integration ? isSupportedIntegration(integration.id) : false;

  const reportIndexFailure = (label: string, error: unknown) => {
    if (!integration) {
      return;
    }
    const message = error instanceof Error ? error.message : 'Unknown error';
    if (isZohoReauthRequired(error)) {
      if (integration.id === 'zoho') {
        const creds = integration.credentials as ZohoCredentials;
        updateIntegrationCredentials(integration.id, {
          ...creds,
          connectionId: '',
          clientSecret: '',
          grantToken: '',
        });
      }
      setIntegrationStatus(integration.id, 'not configured', 'failed');
      setTestStatus('failed');
      toast({
        title: 'Zoho reauth required',
        description:
          'The vaulted refresh token was revoked. Exchange a new grant token, then test connection again.',
      });
      return;
    }
    if (isTransientIndexStatusError(error)) {
      toast({
        title: 'Indexing status check failed',
        description: `${message}. The job may still finish on the server — check the dashboard or retry Save & Index.`,
      });
      return;
    }
    setIntegrationStatus(integration.id, 'not configured', 'failed');
    setTestStatus('failed');
    toast({
      title: `Failed to index ${label}`,
      description: message,
    });
  };

  useEffect(() => {
    if (open) {
      setTestStatus('idle');
      setSaving(false);
      setExchangingGrant(false);
      setIndexJobStatus(null);
      setFullRefresh(overview.freshness === 'stale');
      setAlertsEnabled(false);
      setAlertChannels([]);
      setAlertChannelId('');
    }
    // Default full refresh from freshness only when the modal opens for an integration.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional open-only default
  }, [open, integrationId]);

  useEffect(() => {
    if (!open || integrationId !== 'slack') {
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const token = isTrial ? await getAccessToken() : null;
        const config = await getSlackAlertConfig({
          service: serviceName,
          trialId: isTrial ? trialId ?? undefined : undefined,
          accessToken: token,
        });
        if (cancelled) {
          return;
        }
        setAlertsEnabled(config.enabled);
        setAlertChannelId(config.channelId || '');
      } catch {
        // First-time / backend down — leave defaults.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, integrationId, serviceName, isTrial, trialId, getAccessToken]);

  const handleExchangeGrant = async () => {
    if (!integration || integration.id !== 'zoho') return;
    const creds = integration.credentials as ZohoCredentials;
    if (!creds.clientId?.trim() || !creds.clientSecret?.trim() || !creds.grantToken?.trim()) {
      toast({
        title: 'Zoho credentials incomplete',
        description: 'Client ID, client secret, and grant token are required to exchange.',
      });
      return;
    }
    setExchangingGrant(true);
    try {
      const healthy = await checkAndromediaHealth();
      if (!healthy) {
        throw new Error(
          'Andromedia API unreachable. Check NEXT_PUBLIC_ANDROMEDIA_API_URL (App Runner) or start core on :8083.',
        );
      }
      const token = isTrial ? await getAccessToken() : null;
      const result = await exchangeZohoGrant({
        service: serviceName,
        clientId: creds.clientId.trim(),
        clientSecret: creds.clientSecret.trim(),
        grantToken: creds.grantToken.trim(),
        region: creds.region ?? 'in',
        trialId: isTrial ? trialId ?? undefined : undefined,
        accessToken: token,
      });
      if (!result.connectionId?.trim()) {
        throw new Error('Grant exchange succeeded but no connectionId was returned.');
      }
      updateIntegrationCredentials(integration.id, {
        ...creds,
        connectionId: result.connectionId.trim(),
        grantToken: '',
        clientSecret: '',
        region: (result.region as ZohoRegion | undefined) ?? creds.region,
      });
      setTestStatus('idle');
      toast({
        title: 'Grant exchanged',
        description:
          'Zoho refresh token is vaulted on the server. Run a connection test next.',
      });
    } catch (error) {
      toast({
        title: 'Grant exchange failed',
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setExchangingGrant(false);
    }
  };

  const handleTest = async () => {
    if (!integration || !supported) return;
    setTestStatus('checking');

    try {
      const healthy = await checkAndromediaHealth();
      if (!healthy) {
        throw new Error(
          'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
        );
      }

      if (integration.id === 'slack') {
        const creds = integration.credentials as SlackCredentials;
        if (!creds.botToken?.trim() && !integration.hasSavedSecret) {
          throw new Error('Enter a Slack bot token (xoxb-...) before testing.');
        }
        const token = isTrial ? await getAccessToken() : null;
        const result = await testSlackConnection({
          botToken: creds.botToken.trim(),
          channels: creds.channels?.trim() ?? '',
          trialId: isTrial ? trialId : serviceName,
          accessToken: token,
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        if (creds.botToken?.trim()) {
          markSavedSecret(integration.id, true);
        }
        const missing = result.missingChannels ?? [];
        if (missing.length > 0) {
          toast({
            title: `Connected to ${result.team ?? 'Slack'}`,
            description: `Missing channels: ${missing.join(', ')}. Invite the bot or fix names.`,
          });
        } else {
          toast({
            title: `Connected to ${result.team ?? 'Slack'}`,
            description:
              (result.resolvedChannels?.length ?? 0) > 0
                ? `Resolved: ${result.resolvedChannels?.join(', ')}`
                : 'Token is valid.',
          });
        }
        return;
      }

      if (integration.id === 'git') {
        const creds = integration.credentials as GitCredentials;
        if (!creds.repoPath?.trim()) {
          throw new Error('Enter an absolute repo path on the Andromedia host.');
        }
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        toast({
          title: 'API reachable',
          description:
            'Path will be validated when indexing starts (browser cannot see the server filesystem).',
        });
        return;
      }

      if (integration.id === 'notion') {
        const creds = integration.credentials as NotionCredentials;
        if (!creds.apiKey?.trim() && !integration.hasSavedSecret) {
          throw new Error('Enter a Notion integration token (secret_…) before testing.');
        }
        const token = isTrial ? await getAccessToken() : null;
        const result = await testNotionConnection({
          apiKey: creds.apiKey.trim(),
          pageIds: creds.pageIds?.trim() ?? '',
          trialId: isTrial ? trialId : serviceName,
          accessToken: token,
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        if (creds.apiKey?.trim()) {
          markSavedSecret(integration.id, true);
        }
        const missing = result.missingPageIds ?? [];
        toast({
          title: `Connected to ${result.workspace || 'Notion'}`,
          description:
            missing.length > 0
              ? `Missing pages: ${missing.join(', ')}. Share pages with the integration.`
              : `Signed in as ${result.user || 'bot'}`,
        });
        return;
      }

      if (integration.id === 'jira') {
        const creds = integration.credentials as JiraCredentials;
        if (
          !creds.baseUrl?.trim() ||
          ((!creds.email?.trim() || !creds.apiToken?.trim()) && !integration.hasSavedSecret)
        ) {
          throw new Error('Enter Jira base URL, email, and API token before testing.');
        }
        const token = isTrial ? await getAccessToken() : null;
        const result = await testJiraConnection({
          baseUrl: creds.baseUrl.trim(),
          email: creds.email.trim(),
          apiToken: creds.apiToken.trim(),
          projectKeys: creds.projectKeys?.trim() ?? '',
          trialId: isTrial ? trialId : serviceName,
          accessToken: token,
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        if (creds.apiToken?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: `Connected as ${result.displayName || result.email || 'Jira user'}`,
          description:
            (result.missingProjectKeys?.length ?? 0) > 0
              ? `Missing projects: ${result.missingProjectKeys?.join(', ')}`
              : 'Credentials are valid.',
        });
        return;
      }

      if (integration.id === 'linear') {
        const creds = integration.credentials as LinearCredentials;
        if (!creds.apiKey?.trim() && !integration.hasSavedSecret) {
          throw new Error('Enter a Linear API key (lin_api_…) before testing.');
        }
        const token = isTrial ? await getAccessToken() : null;
        const result = await testLinearConnection({
          apiKey: creds.apiKey.trim(),
          teamKeys: creds.teamKeys?.trim() ?? '',
          trialId: isTrial ? trialId : serviceName,
          accessToken: token,
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        if (creds.apiKey?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: `Connected as ${result.name || 'Linear user'}`,
          description:
            (result.missingTeamKeys?.length ?? 0) > 0
              ? `Missing teams: ${result.missingTeamKeys?.join(', ')}`
              : 'API key is valid.',
        });
        return;
      }

      if (integration.id === 'zoho') {
        const creds = integration.credentials as ZohoCredentials;
        if (!creds.connectionId?.trim()) {
          throw new Error('Exchange a grant token before testing (server connection required).');
        }
        const zohoToken = isTrial ? await getAccessToken() : null;
        const result = await testZohoConnection({
          service: serviceName,
          connectionId: creds.connectionId.trim(),
          trialId: isTrial ? trialId ?? undefined : undefined,
          accessToken: zohoToken,
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        const dealsNote = result.hasDealsModule
          ? 'Deals module is available.'
          : 'Deals module was not found — indexing may fail.';
        toast({
          title: `Connected to Zoho CRM (${result.region ?? creds.region})`,
          description: `${result.moduleCount ?? 0} modules discovered. ${dealsNote}`,
        });
        return;
      }
    } catch (error) {
      if (integration?.id === 'zoho' && isZohoReauthRequired(error)) {
        const creds = integration.credentials as ZohoCredentials;
        updateIntegrationCredentials(integration.id, {
          ...creds,
          connectionId: '',
          clientSecret: '',
          grantToken: '',
        });
        setTestStatus('failed');
        setIntegrationTestStatus(integration.id, 'failed');
        toast({
          title: 'Zoho reauth required',
          description:
            'The vaulted refresh token was revoked. Exchange a new grant token, then test again.',
        });
        return;
      }
      setTestStatus('failed');
      setIntegrationTestStatus(integration.id, 'failed');
      toast({
        title: 'Connection test failed',
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  };

  const handleSave = async () => {
    if (!integration || !supported) return;

    if (integration.id === 'slack') {
      const creds = integration.credentials as SlackCredentials;
      if ((!creds.botToken?.trim() && !integration.hasSavedSecret) || !creds.channels?.trim()) {
        toast({
          title: 'Slack config incomplete',
          description: 'Bot token and channels are required before indexing.',
        });
        return;
      }
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const accessToken = isTrial ? await getAccessToken() : null;
        const started = await startSlackIndex({
          service: serviceName,
          botToken: creds.botToken.trim(),
          channels: creds.channels.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
          trialId: isTrial ? trialId : serviceName,
          accessToken,
        });
        if (creds.botToken?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: 'Slack indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollSlackIndexJob(started.jobId);
        if (isNoNewSlackThreads(job)) {
          const nextIntegrations = integrations.map((item) =>
            item.id === integration.id
              ? {
                  ...item,
                  credentials: creds,
                  status: 'connected' as const,
                  testStatus: 'connected' as const,
                }
              : item,
          );
          save(nextIntegrations);
          setTestStatus('connected');
          toast({
            title: 'No new Slack threads found',
            description:
              typeof job.stats?.message === 'string'
                ? job.stats.message
                : 'Credentials are valid. There is nothing new to index for the selected channels.',
          });
          onClose();
          return;
        }
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Slack indexing failed',
            description: job.error ?? 'Unknown indexing error',
          });
          return;
        }
        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        const threads = job.stats?.threadsIndexed;
        const chunks = job.stats?.chunksIndexed;
        toast({
          title: 'Slack indexing complete',
          description:
            `Indexed ${chunks ?? threads ?? 0} chunks` +
            (threads != null ? ` from ${threads} threads` : ''),
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Slack', error);
      } finally {
        setSaving(false);
      }
      return;
    }

    if (integration.id === 'git') {
      const creds = integration.credentials as GitCredentials;
      if (!creds.repoPath?.trim()) {
        toast({
          title: 'Git config incomplete',
          description: 'Repo path is required before indexing.',
        });
        return;
      }
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const localPath = creds.repoPath.trim();
        const started = await startCodeIndex({ localPath, embed: false });
        toast({
          title: 'Code indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const codeJob = await pollCodeIndexJob(started.jobId);
        if (codeJob.status === 'failed') {
          throw new Error(codeJob.error ?? 'Code indexing failed');
        }

        let historyNote = '';
        if (creds.commitHistory) {
          const historyStarted = await startHistoryIndex({
            localPath,
            embed: false,
            since: sinceIsoFromDays(creds.maxCommitHistoryDays ?? 90),
          });
          toast({
            title: 'Git history indexing started',
            description: `Job ${historyStarted.jobId} — waiting for completion…`,
          });
          const historyJob = await pollHistoryIndexJob(historyStarted.jobId);
          if (historyJob.status === 'failed') {
            throw new Error(historyJob.error ?? 'Git history indexing failed');
          }
          historyNote = ` · history ${historyJob.stats?.chunksIndexed ?? 0} chunks`;
        }

        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        toast({
          title: 'Git indexing complete',
          description: `Code ${codeJob.stats?.chunksIndexed ?? 0} chunks${historyNote}`,
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Git', error);
      } finally {
        setSaving(false);
      }
      return;
    }

    if (integration.id === 'notion') {
      const creds = integration.credentials as NotionCredentials;
      if (!creds.apiKey?.trim() && !integration.hasSavedSecret) {
        toast({
          title: 'Notion config incomplete',
          description: 'Integration token is required before indexing.',
        });
        return;
      }
      if (!creds.pageIds?.trim() && !creds.databaseIds?.trim()) {
        toast({
          title: 'Notion config incomplete',
          description: 'Provide at least one page ID or database ID.',
        });
        return;
      }
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const accessToken = isTrial ? await getAccessToken() : null;
        const started = await startNotionIndex({
          service: serviceName,
          apiKey: creds.apiKey.trim(),
          pageIds: creds.pageIds?.trim() ?? '',
          databaseIds: creds.databaseIds?.trim() ?? '',
          embed: false,
          trialId: isTrial ? trialId : serviceName,
          accessToken,
        });
        if (creds.apiKey?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: 'Notion indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollNotionIndexJob(started.jobId);
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Notion indexing failed',
            description: job.error ?? 'Unknown indexing error',
          });
          return;
        }
        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        toast({
          title: 'Notion indexing complete',
          description: `Indexed ${job.stats?.chunksIndexed ?? 0} chunks from ${job.stats?.pagesIndexed ?? 0} pages`,
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Notion', error);
      } finally {
        setSaving(false);
      }
      return;
    }

    if (integration.id === 'jira') {
      const creds = integration.credentials as JiraCredentials;
      if (
        !creds.baseUrl?.trim() ||
        !creds.projectKeys?.trim() ||
        ((!creds.email?.trim() || !creds.apiToken?.trim()) && !integration.hasSavedSecret)
      ) {
        toast({
          title: 'Jira config incomplete',
          description: 'Base URL, email, API token, and project keys are required.',
        });
        return;
      }
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const accessToken = isTrial ? await getAccessToken() : null;
        const started = await startJiraIndex({
          service: serviceName,
          baseUrl: creds.baseUrl.trim(),
          email: creds.email.trim(),
          apiToken: creds.apiToken.trim(),
          projectKeys: creds.projectKeys.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
          trialId: isTrial ? trialId : serviceName,
          accessToken,
        });
        if (creds.apiToken?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: 'Jira indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollJiraIndexJob(started.jobId);
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Jira indexing failed',
            description: job.error ?? 'Unknown indexing error',
          });
          return;
        }
        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        toast({
          title: 'Jira indexing complete',
          description: `Indexed ${job.stats?.chunksIndexed ?? 0} chunks from ${job.stats?.issuesIndexed ?? 0} issues`,
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Jira', error);
      } finally {
        setSaving(false);
      }
      return;
    }

    if (integration.id === 'linear') {
      const creds = integration.credentials as LinearCredentials;
      if ((!creds.apiKey?.trim() && !integration.hasSavedSecret) || !creds.teamKeys?.trim()) {
        toast({
          title: 'Linear config incomplete',
          description: 'API key and team keys are required before indexing.',
        });
        return;
      }
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const accessToken = isTrial ? await getAccessToken() : null;
        const started = await startLinearIndex({
          service: serviceName,
          apiKey: creds.apiKey.trim(),
          teamKeys: creds.teamKeys.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
          trialId: isTrial ? trialId : serviceName,
          accessToken,
        });
        if (creds.apiKey?.trim()) {
          markSavedSecret(integration.id, true);
        }
        toast({
          title: 'Linear indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollLinearIndexJob(started.jobId);
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Linear indexing failed',
            description: job.error ?? 'Unknown indexing error',
          });
          return;
        }
        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        toast({
          title: 'Linear indexing complete',
          description: `Indexed ${job.stats?.chunksIndexed ?? 0} chunks from ${job.stats?.issuesIndexed ?? 0} issues`,
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Linear', error);
      } finally {
        setSaving(false);
      }
      return;
    }

    if (integration.id === 'zoho') {
      const creds = integration.credentials as ZohoCredentials;
      if (testStatus !== 'connected') {
        toast({
          title: 'Connection test required',
          description: 'Run a successful Zoho connection test before indexing.',
        });
        return;
      }
      if (!creds.connectionId?.trim()) {
        toast({
          title: 'Zoho config incomplete',
          description: 'Exchange a grant token so the server has a vaulted connection before indexing.',
        });
        return;
      }
      setSaving(true);
      setIndexJobStatus('starting');
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        const token = isTrial ? await getAccessToken() : null;
        const started = await startZohoIndex({
          service: serviceName,
          trialId: isTrial ? trialId ?? undefined : undefined,
          accessToken: token,
          connectionId: creds.connectionId.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          minQualityScore: creds.minQualityScore ?? 0.35,
          embed: creds.embed ?? false,
          fullRefresh,
        });
        toast({
          title: fullRefresh ? 'Zoho full refresh started' : 'Zoho indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollZohoIndexJob(started.jobId, {
          onProgress: (progress) => setIndexJobStatus(progress.status),
        });
        if (job.status === 'failed') {
          // Keep verify status — indexing failure is usually embeddings/LLM, not Zoho auth.
          setIntegrationStatus(integration.id, 'connected', 'connected');
          setTestStatus('connected');
          const err = job.error ?? 'Unknown indexing error';
          const embedHint =
            /embedding|openrouter|402|payment required|credits/i.test(err)
              ? ' Tip: turn off “Generate embeddings during index” and retry, or add OpenRouter credits.'
              : '';
          toast({
            title: 'Zoho indexing failed',
            description: `${err}${embedHint}`,
          });
          return;
        }
        const nextIntegrations = integrations.map((item) =>
          item.id === integration.id
            ? {
                ...item,
                credentials: creds,
                status: 'connected' as const,
                testStatus: 'connected' as const,
              }
            : item,
        );
        save(nextIntegrations);
        setTestStatus('connected');
        toast({
          title: job.stats?.newZohoIndex
            ? 'Zoho indexing complete (new CRM account index)'
            : 'Zoho indexing complete',
          description: job.stats?.newZohoIndex
            ? `Fresh index for this Zoho account — ${job.stats?.chunksIndexed ?? 0} chunks (previous account index kept separately).`
            : `Fetched ${job.stats?.dealsFetched ?? 0} deals · ` +
              `indexed ${job.stats?.dealsIndexed ?? 0} · ` +
              `skipped ${job.stats?.dealsSkipped ?? 0} · ` +
              `${job.stats?.chunksIndexed ?? 0} chunks`,
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Zoho', error);
      } finally {
        setSaving(false);
        setIndexJobStatus(null);
      }
    }
  };

  const handleOpenChange = (isOpen: boolean) => {
    if (!isOpen) {
      if (saving) {
        return;
      }
      setTestStatus('idle');
      onClose();
    }
  };

  const loadAlertChannels = async () => {
    if (!integration || integration.id !== 'slack') {
      return;
    }
    const creds = integration.credentials as SlackCredentials;
    if (!creds.botToken?.trim()) {
      toast({
        title: 'Bot token required',
        description: 'Enter a Slack bot token before loading channels.',
      });
      return;
    }
    setAlertChannelsLoading(true);
    try {
      const channels = await listSlackChannels({ botToken: creds.botToken.trim() });
      setAlertChannels(channels);
      if (!alertChannelId && channels.length > 0) {
        const preferred = channels.find((channel) => channel.isMember) ?? channels[0];
        setAlertChannelId(preferred.id);
      }
    } catch (error) {
      toast({
        title: 'Unable to list channels',
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setAlertChannelsLoading(false);
    }
  };

  const savePipelineAlerts = async () => {
    if (!integration || integration.id !== 'slack') {
      return;
    }
    const creds = integration.credentials as SlackCredentials;
    if (alertsEnabled && !creds.botToken?.trim()) {
      toast({
        title: 'Bot token required',
        description: 'Enter a Slack bot token before enabling pipeline alerts.',
      });
      return;
    }
    if (alertsEnabled && !alertChannelId) {
      toast({
        title: 'Alert channel required',
        description: 'Load channels and pick where Andro should post pipeline alerts.',
      });
      return;
    }
    setAlertsSaving(true);
    try {
      const token = isTrial ? await getAccessToken() : null;
      const selected = alertChannels.find((channel) => channel.id === alertChannelId);
      await putSlackAlertConfig({
        service: serviceName,
        trialId: isTrial ? trialId ?? undefined : undefined,
        accessToken: token,
        enabled: alertsEnabled,
        botToken: creds.botToken.trim(),
        channelId: alertChannelId,
        channelName: selected?.name ?? '',
      });
      toast({
        title: alertsEnabled ? 'Pipeline alerts enabled' : 'Pipeline alerts saved',
        description: alertsEnabled
          ? `Alerts will post to #${selected?.name || alertChannelId} after Zoho reindex.`
          : 'Alerts remain off until you enable them.',
      });
    } catch (error) {
      toast({
        title: 'Unable to save alerts',
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setAlertsSaving(false);
    }
  };

  if (!integration) return null;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[min(85vh,calc(100dvh-2rem))] overflow-y-auto p-4 sm:p-6">
        <DialogCloseButton />
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IntegrationIcon id={integration.id} size="md" />
            {integration.name}
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 mt-4">
          {integration.id === 'zoho' ? (
            <ZohoConfigureFields
              credentials={integration.credentials as ZohoCredentials}
              onUpdate={(patch) =>
                updateIntegrationCredentials(integration.id, {
                  ...integration.credentials,
                  ...patch,
                } as ZohoCredentials)
              }
              onExchangeGrant={handleExchangeGrant}
              exchangingGrant={exchangingGrant}
              testStatus={testStatus}
              onTest={handleTest}
              saving={saving}
              indexJobStatus={indexJobStatus}
              fullRefresh={fullRefresh}
              onFullRefreshChange={setFullRefresh}
            />
          ) : (
            <CredentialFields
              integration={integration}
              onUpdate={(patch) =>
                updateIntegrationCredentials(integration.id, {
                  ...integration.credentials,
                  ...patch,
                } as IntegrationConfig['credentials'])
              }
            />
          )}

          {integration.id === 'slack' ? (
            <div className="space-y-3 rounded-lg border border-border p-4">
              <SectionHeading title="Pipeline alerts" step={2} />
              <p className="text-xs leading-relaxed text-muted-foreground">
                After each successful Zoho reindex, Andro diffs deal flags and posts new stale /
                overdue / hot-stage signals to one Slack channel.
              </p>
              <div className="flex items-center justify-between gap-3">
                <Label className="text-xs text-muted-foreground">Enable pipeline alerts</Label>
                <Switch checked={alertsEnabled} onCheckedChange={setAlertsEnabled} />
              </div>
              <FieldRow label="Alert channel">
                <div className="flex flex-col gap-2 sm:flex-row">
                  <select
                    className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                    value={alertChannelId}
                    onChange={(e) => setAlertChannelId(e.target.value)}
                    disabled={alertChannelsLoading || alertsSaving}
                  >
                    {alertChannels.length === 0 ? (
                      <option value={alertChannelId || ''}>
                        {alertChannelId
                          ? `Saved channel ${alertChannelId}`
                          : 'Load channels to choose'}
                      </option>
                    ) : (
                      alertChannels.map((channel) => (
                        <option key={channel.id} value={channel.id}>
                          #{channel.name}
                          {channel.isMember ? '' : ' (invite bot)'}
                        </option>
                      ))
                    )}
                  </select>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="shrink-0"
                    onClick={() => {
                      void loadAlertChannels();
                    }}
                    disabled={alertChannelsLoading || alertsSaving}
                  >
                    {alertChannelsLoading ? 'loading…' : 'load channels'}
                  </Button>
                </div>
              </FieldRow>
              <div className="flex justify-end">
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  onClick={() => {
                    void savePipelineAlerts();
                  }}
                  disabled={alertsSaving}
                >
                  {alertsSaving ? 'saving…' : 'save alerts'}
                </Button>
              </div>
            </div>
          ) : null}
        </div>

        {supported ? (
          integration.id === 'zoho' ? (
            <div className="flex items-center justify-end mt-6 pt-4 border-t border-border">
              <Button
                type="button"
                size="sm"
                onClick={handleSave}
                disabled={
                  saving ||
                  exchangingGrant ||
                  testStatus === 'checking' ||
                  testStatus !== 'connected'
                }
              >
                {saving ? 'indexing…' : 'save & index'}
              </Button>
            </div>
          ) : (
            <div className="flex items-center justify-between mt-6 pt-4 border-t border-border">
              <div className="flex items-center gap-3">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={handleTest}
                  disabled={testStatus === 'checking' || saving}
                >
                  {testStatus === 'checking' ? 'testing…' : 'test connection'}
                </Button>
                <TestStatusBadge status={testStatus} />
              </div>
              <Button
                type="button"
                size="sm"
                onClick={handleSave}
                disabled={saving || testStatus === 'checking'}
              >
                {saving ? 'indexing…' : 'save & index'}
              </Button>
            </div>
          )
        ) : (
          <div className="mt-6 pt-4 border-t border-border">
            <Button type="button" size="sm" variant="outline" onClick={onClose}>
              close
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
