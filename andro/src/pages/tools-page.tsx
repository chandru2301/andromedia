import { useAndroConfig } from '@/hooks/useAndroConfig';
import { McpToolGridCard } from '@/components/McpToolGridCard';
import { MCP_TOOLS } from '@/types/config';

export function ToolsPage() {
  const { mcp, setMcpTool } = useAndroConfig();

  return (
    <div className="mx-auto max-w-6xl space-y-4">
      <div>
        <h2 className="text-sm text-foreground">Tools</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          Tools exposed by the Andro MCP server when Cursor is connected to the backend.
        </p>
      </div>
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
    </div>
  );
}
