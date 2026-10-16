import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import { MCP_TOOLS, type McpToolId } from "@/types/product-config";

export function McpToolGridCard({
  toolId,
  enabled,
  onToggle,
}: {
  toolId: McpToolId;
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
}) {
  const tool = MCP_TOOLS.find((item) => item.id === toolId);
  if (!tool) {
    return null;
  }

  return (
    <div
      className={cn(
        "rounded-xl border border-border bg-card/80 p-4 transition-colors",
        enabled && "border-primary/30",
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="font-mono text-xs text-foreground">{tool.label}</div>
          <div className="mt-2 text-sm leading-relaxed text-muted-foreground">{tool.description}</div>
        </div>
        <Switch checked={enabled} onCheckedChange={onToggle} />
      </div>
    </div>
  );
}
