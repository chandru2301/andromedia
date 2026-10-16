import { useIndexOverview } from '@/hooks/useIndexOverview';

export function StatBar() {
  const overview = useIndexOverview();

  const stats = [
    {
      label: 'Total chunks',
      value: overview.loading && !overview.raw ? '—' : overview.totalChunks.toLocaleString(),
    },
    {
      label: 'Plugins connected',
      value: overview.loading && !overview.raw ? '—' : String(overview.sourcesConnected),
    },
    {
      label: 'Last sync',
      value: overview.loading && !overview.raw ? '—' : overview.lastSync,
    },
    {
      label: 'Index size',
      value: overview.loading && !overview.raw ? '—' : overview.indexSize,
    },
  ];

  return (
    <div className="flex flex-wrap items-center gap-x-10 gap-y-4 border-b border-border pb-6">
      {stats.map((stat) => (
        <div key={stat.label} className="flex flex-col gap-1">
          <span className="text-[10px] uppercase tracking-wider text-muted-foreground">{stat.label}</span>
          <span className="text-xl text-primary">{stat.value}</span>
        </div>
      ))}
    </div>
  );
}
