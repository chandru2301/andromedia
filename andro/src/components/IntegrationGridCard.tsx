import { IntegrationIcon } from '@/components/IntegrationIcon';
import type { IntegrationConfig } from '@/types/config';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';

interface IntegrationGridCardProps {
  integration: IntegrationConfig;
  comingSoon?: boolean;
  onConfigure: () => void;
  onToggle: (enabled: boolean) => void;
}

function statusBadge(status: IntegrationConfig['status'], comingSoon?: boolean) {
  if (comingSoon) return <Badge variant="muted">coming soon</Badge>;
  if (status === 'connected') return <Badge variant="success">connected</Badge>;
  if (status === 'not configured') return <Badge variant="muted">not configured</Badge>;
  return <Badge variant="muted">disabled</Badge>;
}

export function IntegrationGridCard({
  integration,
  comingSoon = false,
  onConfigure,
  onToggle,
}: IntegrationGridCardProps) {
  const isDisabled = comingSoon || !integration.enabled;

  return (
    <div
      className={cn(
        'flex flex-col rounded-md border border-border bg-card p-4 transition-all duration-200',
        isDisabled && 'opacity-50',
        !comingSoon && integration.status === 'connected' && integration.enabled && 'border-primary/30',
      )}
    >
      <div className="flex items-start justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <IntegrationIcon id={integration.id} size="sm" />
          <span className="text-sm text-foreground truncate">{integration.name}</span>
        </div>
        {statusBadge(integration.status, comingSoon)}
      </div>

      <p className="text-xs text-muted-foreground mb-4 line-clamp-2 flex-1">{integration.description}</p>

      <div className="flex items-center justify-between mt-auto">
        <Switch
          checked={comingSoon ? false : integration.enabled}
          disabled={comingSoon}
          onCheckedChange={onToggle}
        />
        <Button
          type="button"
          variant={
            comingSoon
              ? 'ghost'
              : integration.status === 'not configured'
                ? 'default'
                : 'ghost'
          }
          size="sm"
          disabled={comingSoon}
          onClick={onConfigure}
        >
          {comingSoon ? 'soon' : 'configure'}
        </Button>
      </div>
    </div>
  );
}
