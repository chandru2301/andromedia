import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { IntegrationIcon } from "@/components/app-shell/integration-icon";
import { cn } from "@/lib/utils";
import type { IntegrationConfig } from "@/types/product-config";

function statusBadge(status: IntegrationConfig["status"], comingSoon?: boolean) {
  if (comingSoon) return <Badge variant="outline">SOON</Badge>;
  if (status === "connected") return <Badge>LIVE</Badge>;
  if (status === "not configured") return <Badge variant="outline">SETUP</Badge>;
  return <Badge variant="outline">OFF</Badge>;
}

export function IntegrationGridCard({
  integration,
  comingSoon = false,
  onConfigure,
  onToggle,
}: {
  integration: IntegrationConfig;
  comingSoon?: boolean;
  onConfigure: () => void;
  onToggle: (enabled: boolean) => void;
}) {
  const isDisabled = comingSoon || !integration.enabled;

  return (
    <div
      className={cn(
        "flex flex-col rounded-xl border border-border bg-card/80 p-5 transition-colors",
        isDisabled && "opacity-60",
        !comingSoon && integration.status === "connected" && integration.enabled && "border-primary/30",
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2">
          <div className="rounded-md border border-border bg-background/60 p-2">
            <IntegrationIcon id={integration.id} size="md" />
          </div>
          <div>
            <div className="text-sm font-medium text-foreground">{integration.name}</div>
          </div>
        </div>
        {statusBadge(integration.status, comingSoon)}
      </div>

      <p className="mt-4 flex-1 text-sm leading-relaxed text-muted-foreground">
        {integration.description}
      </p>

      <div className="mt-5 flex flex-col gap-3 border-t border-border/60 pt-4 sm:flex-row sm:items-center sm:justify-between">
        <Switch checked={comingSoon ? false : integration.enabled} disabled={comingSoon} onCheckedChange={onToggle} />
        <Button
          type="button"
          variant={comingSoon ? "ghost" : "outline"}
          size="sm"
          disabled={comingSoon}
          onClick={onConfigure}
          className="w-full sm:w-auto"
        >
          {comingSoon ? "Coming soon" : "Configure"}
        </Button>
      </div>
    </div>
  );
}
