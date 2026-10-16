import { INTEGRATION_META } from '@/data/integrationMeta';
import type { IntegrationId } from '@/types/config';
import { cn } from '@/lib/utils';

interface IntegrationIconProps {
  id: IntegrationId;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const sizeMap = {
  sm: 20,
  md: 24,
  lg: 28,
};

/** Official brand logo SVG (multicolor where provided by the brand). */
export function IntegrationIcon({ id, size = 'sm', className }: IntegrationIconProps) {
  const meta = INTEGRATION_META[id];
  const px = sizeMap[size];

  return (
    <img
      src={meta.logo}
      alt={meta.title}
      width={px}
      height={px}
      className={cn('shrink-0 object-contain', className)}
      draggable={false}
    />
  );
}
