import type { McpToolId } from '@/types/config';
import { MCP_TOOLS } from '@/types/config';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';

interface McpToolGridCardProps {
  toolId: McpToolId;
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
}

export function McpToolGridCard({ toolId, enabled, onToggle }: McpToolGridCardProps) {
  const tool = MCP_TOOLS.find((t) => t.id === toolId)!;

  return (
    <div
      className={cn(
        'flex flex-col rounded-md border border-border bg-card p-3 transition-all duration-200',
        enabled && 'border-primary/20',
      )}
    >
      <div className="flex items-start justify-between gap-2 mb-2">
        <span className="text-xs font-mono text-foreground">{tool.label}</span>
        <Switch checked={enabled} onCheckedChange={onToggle} />
      </div>
      <p className="text-[10px] text-muted-foreground leading-relaxed">{tool.description}</p>
    </div>
  );
}
