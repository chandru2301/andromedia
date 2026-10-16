import { useCallback, useEffect, useState } from 'react';
import {
  ExternalLink,
  FileText,
  HardDrive,
  RefreshCw,
  RotateCcw,
  Terminal,
  Trash2,
  Wrench,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { useTrialSession } from '@/hooks/useTrialSession';
import { checkAndromediaHealth, localAndromediaApiBase } from '@/lib/andromediaApi';
import { cn } from '@/lib/utils';

/**
 * Desktop developer surface: build/runtime facts, index state, and the
 * shortcuts you reach for while developing against a local core.
 *
 * Renders in the browser too — the Electron-only controls are hidden rather
 * than shown broken, so `npm run dev` stays usable.
 */
export function DevPage() {
  const overview = useIndexOverview();
  const { serviceName, isTrialReady } = useTrialSession();
  const bridge = typeof window !== 'undefined' ? window.androDesktop?.dev : undefined;
  const [appInfo, setAppInfo] = useState<AndroAppInfo | null>(null);
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [zoom, setZoom] = useState<number>(1);
  const [busy, setBusy] = useState<string | null>(null);

  const refreshHealth = useCallback(async () => {
    setApiUp(await checkAndromediaHealth());
  }, []);

  useEffect(() => {
    void refreshHealth();
    const id = window.setInterval(() => void refreshHealth(), 15_000);
    return () => window.clearInterval(id);
  }, [refreshHealth]);

  useEffect(() => {
    if (!bridge) {
      return;
    }
    let cancelled = false;
    void bridge
      .appInfo()
      .then((info) => {
        if (!cancelled) {
          setAppInfo(info);
          setZoom(info.zoomFactor);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [bridge]);

  const runZoom = async (direction: 'in' | 'out' | 'reset') => {
    if (!bridge) {
      return;
    }
    setZoom(await bridge.zoom(direction));
  };

  const apiBase = localAndromediaApiBase();

  const facts: { label: string; value: string; mono?: boolean }[] = [
    { label: 'Core API', value: apiBase, mono: true },
    { label: 'API status', value: apiUp === null ? 'checking…' : apiUp ? 'up' : 'unreachable' },
    { label: 'Service', value: serviceName || 'local', mono: true },
    { label: 'Mode', value: isTrialReady ? 'trial' : 'local pro' },
    { label: 'Indexed chunks', value: overview.totalChunks.toLocaleString() },
    { label: 'Sources', value: String(overview.sourcesConnected) },
    { label: 'Index size', value: overview.indexSize },
    { label: 'Last sync', value: overview.lastSync },
  ];

  const runtime: { label: string; value: string }[] = appInfo
    ? [
        { label: 'App version', value: appInfo.version },
        { label: 'Electron', value: appInfo.electron },
        { label: 'Chromium', value: appInfo.chrome },
        { label: 'Node', value: appInfo.node },
        { label: 'Platform', value: `${appInfo.platform} ${appInfo.arch}` },
        { label: 'Build', value: appInfo.packaged ? 'packaged' : 'development' },
        { label: 'User data', value: appInfo.userData },
      ]
    : [];

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-sm text-foreground">Developer</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            Runtime facts and shortcuts for working against a local core.
          </p>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="h-8 gap-1.5 text-xs"
          onClick={() => {
            void refreshHealth();
            void overview.refresh();
          }}
        >
          <RefreshCw className={cn('h-3.5 w-3.5', overview.loading && 'animate-spin')} />
          Refresh
        </Button>
      </div>

      <section className="space-y-2">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">Environment</h3>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
          {facts.map((fact) => (
            <div key={fact.label} className="rounded-md border border-border bg-card px-3 py-2">
              <p className="text-[10px] uppercase tracking-wider text-muted-foreground">
                {fact.label}
              </p>
              <p
                className={cn(
                  'mt-1 truncate text-xs text-foreground',
                  fact.mono && 'font-mono text-[11px]',
                )}
                title={fact.value}
              >
                {fact.value}
              </p>
            </div>
          ))}
        </div>
        {overview.error ? (
          <p className="text-[11px] text-destructive">Index overview: {overview.error}</p>
        ) : null}
      </section>

      {bridge ? (
        <section className="space-y-2">
          <h3 className="text-xs uppercase tracking-wider text-muted-foreground">Desktop</h3>
          <div className="rounded-md border border-border bg-card p-4">
            <dl className="grid grid-cols-1 gap-x-6 gap-y-1.5 sm:grid-cols-2">
              {runtime.map((row) => (
                <div key={row.label} className="flex items-baseline justify-between gap-3">
                  <dt className="text-[11px] text-muted-foreground">{row.label}</dt>
                  <dd
                    className="truncate font-mono text-[11px] text-foreground"
                    title={row.value}
                  >
                    {row.value}
                  </dd>
                </div>
              ))}
            </dl>

            <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-3">
              <span className="text-[11px] text-muted-foreground">Zoom {Math.round(zoom * 100)}%</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-7 px-2 text-[11px]"
                onClick={() => void runZoom('out')}
              >
                −
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-7 px-2 text-[11px]"
                onClick={() => void runZoom('in')}
              >
                +
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-7 px-2 text-[11px]"
                onClick={() => void runZoom('reset')}
              >
                Reset
              </Button>

              <div className="ml-auto flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 gap-1.5 px-2 text-[11px]"
                  onClick={() => {
                    setBusy('devtools');
                    void bridge.toggleDevTools().finally(() => setBusy(null));
                  }}
                  disabled={busy === 'devtools'}
                >
                  <Wrench className="h-3 w-3" />
                  DevTools
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 gap-1.5 px-2 text-[11px]"
                  onClick={() =>
                    void bridge.openLogs().then((dir) => {
                      if (!dir) {
                        setBusy('logs-failed');
                      }
                    })
                  }
                >
                  <FileText className="h-3 w-3" />
                  Logs
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 gap-1.5 px-2 text-[11px]"
                  onClick={() => void bridge.relaunch()}
                >
                  <RotateCcw className="h-3 w-3" />
                  Relaunch
                </Button>
              </div>
            </div>
            {busy === 'logs-failed' ? (
              <p className="mt-2 text-[11px] text-destructive">
                No logs directory yet — run the core API once to create ~/.andromedia/logs.
              </p>
            ) : null}
          </div>
        </section>
      ) : (
        <section className="rounded-md border border-border bg-card p-4">
          <p className="text-[11px] text-muted-foreground">
            Running in the browser. Desktop-only diagnostics (Electron/Chromium versions, DevTools,
            zoom, log folder) appear when the app runs in the Electron shell.
          </p>
        </section>
      )}

      <section className="space-y-2">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">Shortcuts</h3>
        <div className="rounded-md border border-border bg-card p-4">
          <ul className="grid grid-cols-1 gap-y-1.5 sm:grid-cols-2">
            {[
              ['Open settings', 'Ctrl/Cmd + ,'],
              ['Reload app', 'Ctrl/Cmd + R'],
              ['Hard reload', 'Ctrl/Cmd + Shift + R'],
              ['Toggle DevTools', 'Ctrl + Shift + I'],
              ['Zoom in / out', 'Ctrl/Cmd + ±'],
              ['Reset zoom', 'Ctrl/Cmd + 0'],
            ].map(([label, keys]) => (
              <li key={label} className="flex items-baseline justify-between gap-3">
                <span className="text-[11px] text-muted-foreground">{label}</span>
                <kbd className="rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[10px] text-foreground">
                  {keys}
                </kbd>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="space-y-2">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">Index maintenance</h3>
        <div className="rounded-md border border-border bg-card p-4">
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            Re-index from the CLI so code symbols stay current:
          </p>
          <pre className="mt-2 overflow-x-auto rounded border border-border bg-muted/40 p-3 font-mono text-[11px] text-foreground">
{`andro index --recreate .

# then, after changing languages or extractors:
andro index --recreate --embed .`}
          </pre>
          <p className="mt-2 flex items-start gap-1.5 text-[11px] text-muted-foreground">
            <HardDrive className="mt-0.5 h-3 w-3 shrink-0" />
            Indexes live under ~/.andromedia/index/by-service/&lt;service&gt;. Re-indexing is
            required after grammar or chunking changes.
          </p>
        </div>
      </section>

      <section className="space-y-2">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">Core API</h3>
        <div className="flex flex-wrap gap-2">
          {[
            ['Health', `${apiBase}/api/v1/health`],
            ['Overview', `${apiBase}/api/v1/index/services/${serviceName || 'local'}/overview`],
          ].map(([label, url]) => (
            <Button
              key={label}
              type="button"
              variant="outline"
              size="sm"
              className="h-7 gap-1.5 px-2 text-[11px]"
              onClick={() => {
                if (window.androDesktop?.openExternal) {
                  void window.androDesktop.openExternal(url);
                } else {
                  window.open(url, '_blank', 'noopener,noreferrer');
                }
              }}
            >
              <ExternalLink className="h-3 w-3" />
              {label}
            </Button>
          ))}
        </div>
      </section>

      <p className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <Terminal className="h-3 w-3" />
        Actions here never delete index data — <Trash2 className="h-3 w-3" /> wiping is a CLI
        operation so it cannot be triggered by accident.
      </p>
    </div>
  );
}
