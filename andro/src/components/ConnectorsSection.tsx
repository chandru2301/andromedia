import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, Check, Copy, Loader2, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTrialSession } from '@/hooks/useTrialSession';
import { cn } from '@/lib/utils';

const STATE_LABEL: Record<ConnectorState, string> = {
  disabled: 'off',
  connecting: 'connecting…',
  connected: 'live',
  reconnecting: 'reconnecting…',
  error: 'error',
};

const STATE_TONE: Record<ConnectorState, string> = {
  disabled: 'bg-muted text-muted-foreground',
  connecting: 'bg-amber-500/15 text-amber-500',
  connected: 'bg-emerald-500/15 text-emerald-500',
  reconnecting: 'bg-amber-500/15 text-amber-500',
  error: 'bg-red-500/15 text-red-500',
};

/**
 * "Use Andro from ChatGPT / Claude" — one switch, one copyable URL.
 *
 * The desktop app dials out to the Andro relay, so the workspace on this
 * machine becomes reachable from the browser without the user configuring
 * ports, tunnels, tokens or JSON.
 */
export function ConnectorsSection() {
  const bridge = window.androDesktop?.connector;
  const { serviceName } = useTrialSession();

  const [status, setStatus] = useState<ConnectorStatus | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!bridge) return undefined;
    void bridge.status().then(setStatus).catch(() => undefined);
    return bridge.onStatus((next) => {
      setStatus((prev) => ({ ...(prev ?? next), ...next }));
    });
  }, [bridge]);

  const run = useCallback(
    async (key: string, action: () => Promise<ConnectorStatus | boolean>) => {
      setBusy(key);
      setError(null);
      try {
        const result = await action();
        if (typeof result !== 'boolean') {
          setStatus(result);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      } finally {
        setBusy(null);
      }
    },
    [],
  );

  if (!bridge) {
    return (
      <section className="space-y-2">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">
          ChatGPT &amp; Claude connector
        </h3>
        <p className="text-xs text-muted-foreground">
          Available in the Andro desktop app — it keeps the connection to your local
          workspace open.
        </p>
      </section>
    );
  }

  const state = status?.state ?? 'disabled';
  const enabled = state !== 'disabled';

  const handleCopy = async () => {
    if (!status?.connectorUrl) return;
    await navigator.clipboard.writeText(status.connectorUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-xs uppercase tracking-wider text-muted-foreground">
          ChatGPT &amp; Claude connector
        </h3>
        <span
          className={cn(
            'rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide',
            STATE_TONE[state],
          )}
        >
          {STATE_LABEL[state]}
        </span>
      </div>

      <p className="text-xs text-muted-foreground">
        Turn this on to use your indexed workspace from ChatGPT or Claude in the browser.
        Andro keeps an outbound connection open — nothing is exposed on your network, and
        the connection closes when you turn it off or quit the app.
      </p>

      <Button
        type="button"
        variant={enabled ? 'outline' : 'default'}
        className="w-full"
        disabled={busy !== null}
        onClick={() =>
          void run(
            'toggle',
            enabled
              ? () => bridge.disable()
              : () => bridge.enable({ service: serviceName || undefined }),
          )
        }
      >
        {busy === 'toggle' ? (
          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        ) : null}
        {enabled ? 'turn off connector' : 'turn on connector'}
      </Button>

      {status?.connectorUrl && enabled ? (
        <div className="space-y-3">
          <div className="space-y-2">
            <p className="text-[11px] text-muted-foreground">
              Paste this into <strong>ChatGPT → Settings → Connectors → Add</strong> or{' '}
              <strong>Claude → Settings → Connectors → Add custom connector</strong>. It is a
              secret — anyone with the URL can query this workspace.
            </p>
            <div className="flex gap-2">
              <pre className="flex-1 overflow-x-auto rounded-md border border-border bg-background p-3 font-mono text-[10px] leading-relaxed text-muted-foreground">
                {status.connectorUrl}
              </pre>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0 self-start"
                onClick={() => void handleCopy()}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <p className="text-[11px] text-muted-foreground">
              Or install it directly into a local client:
            </p>
            <div className="grid gap-2">
              {(status.clients ?? []).map((client) => (
                <div
                  key={client.clientId}
                  className="flex items-center justify-between gap-2 rounded-md border border-border px-3 py-2"
                >
                  <div className="min-w-0">
                    <p className="text-xs">{client.label}</p>
                    <p className="truncate font-mono text-[10px] text-muted-foreground">
                      {client.present ? client.file : 'not installed on this machine'}
                    </p>
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={busy !== null}
                    onClick={() =>
                      void run(client.clientId, () =>
                        client.installed
                          ? bridge.removeClient(client.clientId)
                          : bridge.installClient(client.clientId),
                      )
                    }
                  >
                    {busy === client.clientId ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : client.installed ? (
                      'remove'
                    ) : (
                      'install'
                    )}
                  </Button>
                </div>
              ))}
            </div>
          </div>

          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy !== null}
              onClick={() => void run('rotate', () => bridge.rotate())}
            >
              <RefreshCw className="mr-2 h-3.5 w-3.5" />
              rotate URL
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy !== null}
              onClick={() => void run('unpair', () => bridge.unpair())}
            >
              unpair this machine
            </Button>
          </div>
          <p className="text-[10px] text-muted-foreground">
            Rotating invalidates the old URL — re-paste or re-install afterwards.
          </p>
        </div>
      ) : null}

      {error || status?.lastError ? (
        <p className="flex items-start gap-2 text-[11px] text-red-500">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{error ?? status?.lastError}</span>
        </p>
      ) : null}
    </section>
  );
}
