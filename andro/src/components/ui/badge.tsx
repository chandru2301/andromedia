import { cn } from '@/lib/utils';

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: 'default' | 'success' | 'warning' | 'muted' | 'destructive';
}

export function Badge({ className, variant = 'default', ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-2 py-0.5 text-xs border',
        variant === 'default' && 'border-border text-foreground bg-muted',
        variant === 'success' && 'border-primary text-primary bg-transparent',
        variant === 'warning' && 'border-warning text-warning bg-transparent',
        variant === 'muted' && 'border-border text-muted-foreground bg-transparent',
        variant === 'destructive' && 'border-destructive text-destructive bg-transparent',
        className,
      )}
      {...props}
    />
  );
}
