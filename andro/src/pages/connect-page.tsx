import { useMemo } from 'react';
import { useAndroConfig, buildClaudeSnippet } from '@/hooks/useAndroConfig';
import { CopySnippet } from '@/components/CopySnippet';
import { ClientIcon, type ClientIconId } from '@/components/ClientIcon';

function buildClaudeCodeCommand(transport: 'stdio' | 'sse', ssePort: number): string {
  if (transport === 'stdio') {
    return 'claude mcp add andro -- node /home/chan/Documents/andro-mcp/server.js';
  }
  return `claude mcp add --transport sse andro http://localhost:${ssePort}/sse`;
}

const CLIENTS: {
  id: string;
  name: string;
  description: string;
  icon: ClientIconId;
}[] = [
  {
    id: 'claude-code',
    name: 'Claude Code',
    description: 'Add the server with a single CLI command — no config file to edit.',
    icon: 'claude',
  },
  {
    id: 'cursor',
    name: 'Cursor',
    description: 'Paste into .cursor/mcp.json (project) or ~/.cursor/mcp.json (global).',
    icon: 'cursor',
  },
  {
    id: 'claude-desktop',
    name: 'Claude Desktop',
    description: 'Paste into your claude_desktop_config.json, then restart Claude Desktop.',
    icon: 'claude',
  },
  {
    id: 'windsurf',
    name: 'Windsurf',
    description: 'Paste into ~/.codeium/windsurf/mcp_config.json.',
    icon: 'windsurf',
  },
];

export function ConnectPage() {
  const { mcp } = useAndroConfig();

  const jsonSnippet = useMemo(() => buildClaudeSnippet(mcp), [mcp]);
  const claudeCodeCommand = useMemo(
    () => buildClaudeCodeCommand(mcp.transport, mcp.ssePort),
    [mcp.transport, mcp.ssePort],
  );

  return (
    <div className="mx-auto max-w-6xl space-y-4">
      <div>
        <h2 className="text-sm text-foreground">Connect</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Point your agent at the Andro MCP server ({mcp.transport === 'stdio' ? 'stdio' : `SSE · port ${mcp.ssePort}`})
          — change transport in Settings.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {CLIENTS.map((client) => (
          <div
            key={client.id}
            className="flex flex-col gap-3 rounded-xl border border-border bg-card p-4"
          >
            <div className="flex items-start gap-2.5">
              <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-border bg-background">
                <ClientIcon id={client.icon} size={16} />
              </span>
              <div>
                <h3 className="text-sm font-medium text-foreground">{client.name}</h3>
                <p className="mt-0.5 text-xs text-muted-foreground">{client.description}</p>
              </div>
            </div>
            <CopySnippet value={client.id === 'claude-code' ? claudeCodeCommand : jsonSnippet} />
          </div>
        ))}
      </div>
    </div>
  );
}
