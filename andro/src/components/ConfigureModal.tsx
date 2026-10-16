import { useState, useEffect } from 'react';
import { IntegrationIcon } from '@/components/IntegrationIcon';
import { useAndroConfig } from '@/hooks/useAndroConfig';
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
  resolveService,
  AndromediaApiError,
  checkAndromediaHealth,
  pollCodeIndexJob,
  pollGoogleChatIndexJob,
  pollHistoryIndexJob,
  pollJiraIndexJob,
  pollLinearIndexJob,
  pollNotionIndexJob,
  pollSlackIndexJob,
  pollZohoIndexJob,
  startCodeIndex,
  startGoogleChatIndex,
  startHistoryIndex,
  startJiraIndex,
  startLinearIndex,
  startNotionIndex,
  startSlackIndex,
  startZohoIndex,
  testGoogleChatConnection,
  testJiraConnection,
  testLinearConnection,
  testNotionConnection,
  testSlackConnection,
  testZohoConnection,
  isNoNewGoogleChatThreads,
  isNoNewSlackThreads,
} from '@/lib/andromediaApi';
import type {
  GitCredentials,
  GoogleChatCredentials,
  IntegrationConfig,
  IntegrationId,
  JiraCredentials,
  LinearCredentials,
  NotionCredentials,
  SlackCredentials,
  TestStatus,
  ZohoCredentials,
  ZohoRegion,
} from '@/types/config';
import { isSupportedIntegration } from '@/types/config';
import { useToast } from '@/components/ui/use-toast';
import { OAuthConnectButton } from '@/components/OAuthConnectButton';

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
  testStatus,
  onTest,
  saving,
  indexJobStatus,
}: {
  credentials: ZohoCredentials;
  onUpdate: (patch: Partial<ZohoCredentials>) => void;
  testStatus: TestStatus;
  onTest: () => void;
  saving: boolean;
  indexJobStatus: string | null;
}) {
  const hasConnection = Boolean(credentials.connectionId?.trim());

  return (
    <div className="space-y-5">
      <div className="space-y-3 rounded-lg border border-border p-4">
        <SectionHeading title="Connect" step={1} />
        <OAuthConnectButton
          provider="zoho"
          label="Zoho"
          description="Connect your Zoho CRM account securely. Andro stores the OAuth credentials on the server; this browser only receives a connection reference."
          onStatusChange={(status) => {
            const connectionId = String(status.metadata?.connectionId ?? '');
            const region = String(status.metadata?.region ?? '');
            if (connectionId && connectionId !== credentials.connectionId) {
              onUpdate({
                connectionId,
                region: (region === 'in' ? 'in' : 'com') as ZohoRegion,
              });
            }
          }}
        />
        <p className="text-xs text-muted-foreground leading-relaxed">
          OAuth credentials are stored securely on the Andro server. No refresh token or client
          secret is exposed to this browser.
        </p>
      </div>

      <div className="space-y-3 rounded-lg border border-border p-4">
        <SectionHeading title="Verify" step={2} />
        <p className="text-xs text-muted-foreground">
          {hasConnection
            ? 'Zoho CRM account is connected and ready to verify.'
            : 'Connect your Zoho CRM account above first.'}
        </p>
        <div className="flex items-center gap-3">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onTest}
            disabled={!hasConnection || testStatus === 'checking' || saving}
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
          <OAuthConnectButton
            provider="slack"
            label="Slack"
            description="Connect your Slack workspace to index messages and threads."
          />
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
          <OAuthConnectButton
            provider="notion"
            label="Notion"
            description="Connect your Notion workspace to index pages and databases."
          />
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
    case 'google_chat': {
      const c = creds as GoogleChatCredentials;
      return (
        <>
          <OAuthConnectButton
            provider="google_chat"
            label="Google Chat"
            description="Connect Google Chat to index messages and threads from selected spaces."
          />
          <FieldRow label="Spaces (optional, comma-separated space names)">
            <Input
              value={c.spaces}
              placeholder="Engineering, General"
              onChange={(e) => onUpdate({ spaces: e.target.value })}
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
    case 'jira': {
      const c = creds as JiraCredentials;
      return (
        <>
          <OAuthConnectButton
            provider="jira"
            label="Jira"
            description="Connect your Atlassian account to access Jira Cloud issues and project history."
          />
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
          <OAuthConnectButton
            provider="linear"
            label="Linear"
            description="Connect your Linear workspace to index issues, cycles, and project updates."
          />
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
  if (status === 'checking') return <Badge variant="muted">checking...</Badge>;
  if (status === 'connected') return <Badge variant="success">connected</Badge>;
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
    save,
  } = useAndroConfig();
  const { toast } = useToast();
  const [testStatus, setTestStatus] = useState<TestStatus>('idle');
  const [saving, setSaving] = useState(false);
  const [indexJobStatus, setIndexJobStatus] = useState<string | null>(null);

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
        });
      }
      setIntegrationStatus(integration.id, 'not configured', 'failed');
      setTestStatus('failed');
      toast({
        title: 'Zoho reauth required',
        description: 'The vaulted refresh token was revoked. Log in with Zoho again, then test connection.',
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
      setIndexJobStatus(null);
    }
  }, [open, integrationId]);

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
        // The OAuth Connect button vaults the bot token server-side; the backend resolves it from
        // there when this is blank, so nothing to guard client-side beyond having logged in.
        const result = await testSlackConnection({
          botToken: '',
          channels: creds.channels?.trim() ?? '',
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
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
        const result = await testNotionConnection({
          apiKey: '',
          pageIds: creds.pageIds?.trim() ?? '',
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
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
        const result = await testJiraConnection({
          projectKeys: creds.projectKeys?.trim() ?? '',
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
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
        const result = await testLinearConnection({
          teamKeys: creds.teamKeys?.trim() ?? '',
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        toast({
          title: `Connected as ${result.name || 'Linear user'}`,
          description:
            (result.missingTeamKeys?.length ?? 0) > 0
              ? `Missing teams: ${result.missingTeamKeys?.join(', ')}`
              : 'API key is valid.',
        });
        return;
      }

      if (integration.id === 'google_chat') {
        const creds = integration.credentials as GoogleChatCredentials;
        // The OAuth Connect button stores the access token server-side; the backend resolves it
        // from the vault, so nothing credential-shaped is sent from here at all.
        const result = await testGoogleChatConnection({
          service: resolveService(),
          spaces: creds.spaces?.trim() ?? '',
        });
        setTestStatus('connected');
        setIntegrationTestStatus(integration.id, 'connected');
        const missing = result.missingSpaces ?? [];
        toast({
          title: `Connected to Google Chat (${result.totalSpaces ?? 0} spaces)`,
          description:
            missing.length > 0
              ? `Missing spaces: ${missing.join(', ')}.`
              : (result.resolvedSpaces?.length ?? 0) > 0
                ? `Resolved: ${result.resolvedSpaces?.join(', ')}`
                : 'Connection is valid.',
        });
        return;
      }

      if (integration.id === 'zoho') {
        const creds = integration.credentials as ZohoCredentials;
        if (!creds.connectionId?.trim()) {
          throw new Error('Log in with Zoho before testing (server connection required).');
        }
        const result = await testZohoConnection({
          service: resolveService(),
          connectionId: creds.connectionId.trim(),
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
      }
    } catch (error) {
      if (integration?.id === 'zoho' && isZohoReauthRequired(error)) {
        const creds = integration.credentials as ZohoCredentials;
        updateIntegrationCredentials(integration.id, {
          ...creds,
          connectionId: '',
        });
        setTestStatus('failed');
        setIntegrationTestStatus(integration.id, 'failed');
        toast({
          title: 'Zoho reauth required',
          description: 'The vaulted refresh token was revoked. Log in with Zoho again, then test.',
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
      if (!creds.channels?.trim()) {
        toast({
          title: 'Slack config incomplete',
          description: 'Channels are required before indexing.',
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
        const started = await startSlackIndex({
          botToken: '',
          channels: creds.channels.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
        });
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

    if (integration.id === 'google_chat') {
      const creds = integration.credentials as GoogleChatCredentials;
      setSaving(true);
      try {
        const healthy = await checkAndromediaHealth();
        if (!healthy) {
          throw new Error(
            'Andromedia API unreachable. Start the core server on :8083 or set VITE_ANDROMEDIA_API_URL.',
          );
        }
        // The OAuth Connect button above vaults the access token server-side; nothing
        // credential-shaped is sent from here.
        const started = await startGoogleChatIndex({
          service: resolveService(),
          spaces: creds.spaces?.trim() ?? '',
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
        });
        toast({
          title: 'Google Chat indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollGoogleChatIndexJob(started.jobId);
        if (isNoNewGoogleChatThreads(job)) {
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
            title: 'No new Google Chat threads found',
            description:
              typeof job.stats?.message === 'string'
                ? job.stats.message
                : 'Connection is valid. There is nothing new to index for the selected spaces.',
          });
          onClose();
          return;
        }
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Google Chat indexing failed',
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
          title: 'Google Chat indexing complete',
          description:
            `Indexed ${chunks ?? threads ?? 0} chunks` +
            (threads != null ? ` from ${threads} threads` : ''),
        });
        onClose();
      } catch (error) {
        reportIndexFailure('Google Chat', error);
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
        const started = await startNotionIndex({
          apiKey: '',
          pageIds: creds.pageIds?.trim() ?? '',
          databaseIds: creds.databaseIds?.trim() ?? '',
          embed: false,
        });
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
      if (!creds.projectKeys?.trim()) {
        toast({
          title: 'Jira config incomplete',
          description: 'Project keys are required before indexing.',
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
        const started = await startJiraIndex({
          projectKeys: creds.projectKeys.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
        });
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
      if (!creds.teamKeys?.trim()) {
        toast({
          title: 'Linear config incomplete',
          description: 'Team keys are required before indexing.',
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
        const started = await startLinearIndex({
          teamKeys: creds.teamKeys.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          embed: false,
        });
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
      if (
        !creds.connectionId?.trim()
      ) {
        toast({
          title: 'Zoho config incomplete',
          description: 'Log in with Zoho above so the server has a vaulted connection before indexing.',
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
        const started = await startZohoIndex({
          service: resolveService(),
          connectionId: creds.connectionId.trim(),
          lookbackDays: creds.lookbackDays ?? 90,
          minQualityScore: creds.minQualityScore ?? 0.35,
          embed: creds.embed ?? false,
        });
        toast({
          title: 'Zoho indexing started',
          description: `Job ${started.jobId} — waiting for completion…`,
        });
        const job = await pollZohoIndexJob(started.jobId, {
          onProgress: (progress) => setIndexJobStatus(progress.status),
        });
        if (job.status === 'failed') {
          setIntegrationStatus(integration.id, 'not configured', 'failed');
          setTestStatus('failed');
          toast({
            title: 'Zoho indexing failed',
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
          title: 'Zoho indexing complete',
          description:
            `Fetched ${job.stats?.dealsFetched ?? 0} deals · ` +
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

  if (!integration) return null;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
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
              testStatus={testStatus}
              onTest={handleTest}
              saving={saving}
              indexJobStatus={indexJobStatus}
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
        </div>

        {supported ? (
          integration.id === 'zoho' ? (
            <div className="flex items-center justify-end mt-6 pt-4 border-t border-border">
              <Button
                type="button"
                size="sm"
                onClick={handleSave}
                disabled={saving || testStatus === 'checking' || testStatus !== 'connected'}
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
