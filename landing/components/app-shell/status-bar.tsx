"use client";

import { useIndexOverview } from "@/hooks/use-index-overview";
import { useTrial } from "@/hooks/use-trial";
import type { IndexFreshness } from "@/lib/api/andromedia";

function freshnessLabel(freshness: IndexFreshness): string | null {
  if (freshness === "fresh" || freshness === "unknown") {
    return null;
  }
  return freshness;
}

export function StatusBar() {
  const { serviceName, isTrial, trialId, remaining, maxQueries, getAccessToken } = useTrial();
  const overview = useIndexOverview(
    serviceName,
    isTrial && trialId ? trialId : undefined,
    isTrial ? getAccessToken : undefined,
  );

  const syncBadge = freshnessLabel(overview.freshness);
  const lastSyncValue =
    overview.loading && !overview.raw
      ? "—"
      : syncBadge
        ? `${overview.lastSync} · ${syncBadge}`
        : overview.lastSync;

  const items = [
    {
      label: "service",
      value: isTrial ? "trial" : serviceName,
      dot: true,
    },
    ...(isTrial && remaining !== null && maxQueries !== null
      ? [
          {
            label: "trial questions",
            value: `${remaining} / ${maxQueries} left`,
          },
        ]
      : []),
    {
      label: "chunks",
      value: overview.loading && !overview.raw ? "—" : overview.totalChunks.toLocaleString(),
    },
    {
      label: "last sync",
      value: lastSyncValue,
      warn: overview.freshness === "stale",
    },
    {
      label: "index",
      value: overview.exists ? overview.indexSize : "no index",
    },
  ];

  return (
    <footer className="border-t border-border/60 bg-background/95 backdrop-blur max-md:mb-[calc(4.25rem+env(safe-area-inset-bottom))]">
      <div className="mx-auto grid max-w-6xl grid-cols-2 gap-x-4 gap-y-2 px-4 py-3 text-[10px] text-muted-foreground sm:flex sm:flex-wrap sm:items-center sm:justify-center sm:gap-x-6 sm:gap-y-2 sm:px-6 sm:text-[11px]">
        {items.map((item) => (
          <span key={item.label} className="flex min-w-0 items-center gap-1.5">
            {"dot" in item && item.dot ? (
              <span className="size-1.5 shrink-0 rounded-full bg-primary" />
            ) : null}
            <span className="shrink-0 uppercase tracking-[0.12em]">{item.label}:</span>
            <span
              className={`truncate ${
                "warn" in item && item.warn ? "text-warning" : ""
              }`}
            >
              {item.value}
            </span>
          </span>
        ))}
      </div>
    </footer>
  );
}
