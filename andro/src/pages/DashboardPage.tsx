import { useEffect, useMemo, useState } from 'react';
import { useAndroConfig } from '@/hooks/useAndroConfig';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { StatBar } from '@/components/StatBar';
import { IntegrationGridCard } from '@/components/IntegrationGridCard';
import { ConfigureModal } from '@/components/ConfigureModal';
import { isSupportedIntegration } from '@/types/config';
import type { ConnectionStatus, IntegrationConfig, IntegrationId } from '@/types/config';

function withLiveStatus(
  integration: IntegrationConfig,
  sourceTypes: string[],
): IntegrationConfig {
  if (!isSupportedIntegration(integration.id)) {
    return { ...integration, enabled: false, status: 'disabled' };
  }
  let status: ConnectionStatus = integration.status;
  if (integration.id === 'git') {
    status =
      sourceTypes.includes('CODE') || sourceTypes.includes('GIT')
        ? 'connected'
        : integration.status === 'connected'
          ? 'not configured'
          : integration.status === 'disabled'
            ? 'disabled'
            : 'not configured';
  }
  if (integration.id === 'slack') {
    status = sourceTypes.includes('SLACK')
      ? 'connected'
      : integration.status === 'connected'
        ? 'not configured'
        : integration.status === 'disabled'
          ? 'disabled'
          : 'not configured';
  }
  if (integration.id === 'notion') {
    status = sourceTypes.includes('NOTION')
      ? 'connected'
      : integration.status === 'connected'
        ? 'not configured'
        : integration.status === 'disabled'
          ? 'disabled'
          : 'not configured';
  }
  if (integration.id === 'jira') {
    status = sourceTypes.includes('JIRA')
      ? 'connected'
      : integration.status === 'connected'
        ? 'not configured'
        : integration.status === 'disabled'
          ? 'disabled'
          : 'not configured';
  }
  if (integration.id === 'linear') {
    status = sourceTypes.includes('LINEAR')
      ? 'connected'
      : integration.status === 'connected'
        ? 'not configured'
        : integration.status === 'disabled'
          ? 'disabled'
          : 'not configured';
  }
  if (integration.id === 'zoho') {
    status = sourceTypes.includes('ZOHO')
      ? 'connected'
      : integration.status === 'connected'
        ? 'not configured'
        : integration.status === 'disabled'
          ? 'disabled'
          : 'not configured';
  }
  return { ...integration, status };
}

export function DashboardPage() {
  const { integrations, setIntegrationEnabled } = useAndroConfig();
  const overview = useIndexOverview();
  const [configureId, setConfigureId] = useState<IntegrationId | null>(null);

  const sourceTypes = overview.raw?.sourceTypes ?? [];
  const displayIntegrations = useMemo(
    () => integrations.map((item) => withLiveStatus(item, sourceTypes)),
    [integrations, sourceTypes],
  );

  const supported = displayIntegrations.filter((i) => isSupportedIntegration(i.id));
  const connectedCount = supported.filter((i) => i.status === 'connected').length;

  useEffect(() => {
    // keep overview fresh after configure closes via focus refresh in hook
  }, [configureId]);

  return (
    <div className="mx-auto max-w-6xl space-y-10">
      <StatBar />

      <section>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-sm text-foreground">Plugins</h2>
          <span className="text-xs text-muted-foreground">
            {connectedCount} of {supported.length} connected
          </span>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {displayIntegrations.map((integration) => (
            <IntegrationGridCard
              key={integration.id}
              integration={integration}
              comingSoon={!isSupportedIntegration(integration.id)}
              onConfigure={() => setConfigureId(integration.id)}
              onToggle={(enabled) => setIntegrationEnabled(integration.id, enabled)}
            />
          ))}
        </div>
      </section>

      <ConfigureModal
        integrationId={configureId}
        onClose={() => {
          setConfigureId(null);
          void overview.refresh();
        }}
      />
    </div>
  );
}
