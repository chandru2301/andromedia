"use client";

import { useIndexOverview } from "@/hooks/use-index-overview";
import { useTrial } from "@/hooks/use-trial";
import type { IndexFreshness } from "@/lib/api/andromedia";

function lastSyncDisplay(lastSync: string, freshness: IndexFreshness, loading: boolean, hasRaw: boolean) {
  if (loading && !hasRaw) {
    return "—";
  }
  if (freshness === "aging" || freshness === "stale") {
    return `${lastSync} · ${freshness}`;
  }
  return lastSync;
}

export function StatBar() {
  const { serviceName, isTrial, trialId, getAccessToken } = useTrial();
  const overview = useIndexOverview(
    serviceName,
    isTrial && trialId ? trialId : undefined,
    isTrial ? getAccessToken : undefined,
  );

  const stats = [
    {
      label: "Total chunks",
      value: overview.loading && !overview.raw ? "—" : overview.totalChunks.toLocaleString(),
    },
    {
      label: "Sources connected",
      value: overview.loading && !overview.raw ? "—" : String(overview.sourcesConnected),
    },
    {
      label: "Last sync",
      value: lastSyncDisplay(
        overview.lastSync,
        overview.freshness,
        overview.loading,
        Boolean(overview.raw),
      ),
      warn: overview.freshness === "stale",
    },
    {
      label: "Index size",
      value: overview.loading && !overview.raw ? "—" : overview.indexSize,
    },
  ];

  return (
    <div className="grid grid-cols-2 gap-3 rounded-2xl border border-border bg-card/70 p-4 sm:gap-4 sm:p-6 md:grid-cols-4">
      {stats.map((stat) => (
        <div key={stat.label} className="min-w-0 space-y-1.5 sm:space-y-2">
          <div className="font-mono text-[10px] uppercase tracking-[0.16em] text-muted-foreground sm:text-[11px] sm:tracking-[0.18em]">
            {stat.label}
          </div>
          <div
            className={`truncate text-xl font-medium sm:text-2xl ${
              "warn" in stat && stat.warn ? "text-warning" : "text-foreground"
            }`}
          >
            {stat.value}
          </div>
        </div>
      ))}
    </div>
  );
}
