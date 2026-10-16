"use client";

import { useEffect, useMemo, useState } from "react";
import { ConfigureModal } from "@/components/app-shell/configure-modal";
import { IntegrationGridCard } from "@/components/app-shell/integration-grid-card";
import { McpToolGridCard } from "@/components/app-shell/mcp-tool-grid-card";
import { StatBar } from "@/components/app-shell/stat-bar";
import { useAndroConfig } from "@/hooks/use-andro-config";
import { useIndexOverview } from "@/hooks/use-index-overview";
import { useTrial } from "@/hooks/use-trial";
import { MCP_TOOLS, isSupportedIntegration } from "@/types/product-config";
import type { ConnectionStatus, IntegrationConfig, IntegrationId } from "@/types/product-config";

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
  const { integrations, setIntegrationEnabled, mcp, setMcpTool } = useAndroConfig();
  const { serviceName, isTrial, trialId, getAccessToken } = useTrial();
  const overview = useIndexOverview(
    serviceName,
    isTrial && trialId ? trialId : undefined,
    isTrial ? getAccessToken : undefined,
  );
  const [configureId, setConfigureId] = useState<IntegrationId | null>(null);

  const sourceTypes = overview.raw?.sourceTypes ?? [];
  const displayIntegrations = useMemo(
    () => integrations.map((item) => withLiveStatus(item, sourceTypes)),
    [integrations, sourceTypes],
  );

  const supported = displayIntegrations.filter((i) => isSupportedIntegration(i.id));
  const connectedCount = supported.filter((i) => i.status === 'connected').length;

  return (
    <div className="space-y-8 sm:space-y-10">
      <StatBar />

      <section>
        <div className="mb-4 flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-sm text-foreground">Sources</h2>
          <span className="text-xs text-muted-foreground">
            {connectedCount} of {supported.length} connected
          </span>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {displayIntegrations.map((integration) => (
            <IntegrationGridCard
              key={integration.id}
              integration={integration}
              comingSoon={
                isTrial
                  ? // Hosted trial: unlock cloud sources; Git needs a local workspace path.
                    !["slack", "notion", "jira", "linear", "zoho"].includes(integration.id)
                  : !isSupportedIntegration(integration.id)
              }
              onConfigure={() => setConfigureId(integration.id)}
              onToggle={(enabled) => setIntegrationEnabled(integration.id, enabled)}
            />
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-4 text-sm text-foreground">MCP tools</h2>
        <p className="mb-4 text-sm leading-7 text-muted-foreground">
          Tools exposed by the Andro MCP server when Cursor is connected to the backend.
        </p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {MCP_TOOLS.map((tool) => (
            <McpToolGridCard
              key={tool.id}
              toolId={tool.id}
              enabled={mcp.tools[tool.id] ?? true}
              onToggle={(enabled) => setMcpTool(tool.id, enabled)}
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
