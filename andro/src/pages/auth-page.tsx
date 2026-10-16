import { useCallback, useEffect, useState } from 'react';
import { ExternalLink, LoaderCircle } from 'lucide-react';
import { AndroLogo } from '@/components/AndroLogo';
import { Button } from '@/components/ui/button';
import { useAppMode } from '@/hooks/useAppMode';
import { useDesktopAuth } from '@/hooks/useDesktopAuth';
import { andromediaApiBase, hostedAndromediaApiBase } from '@/lib/andromediaApi';

type AuthMode = 'sign-in' | 'sign-up';
type WaitingState = null | AuthMode;

function authWebBase(): string {
  const fromEnv = import.meta.env.VITE_AUTH_WEB_URL?.trim();
  if (fromEnv) {
    return fromEnv.replace(/\/$/, '');
  }
  return 'https://andro-app.vercel.app';
}

async function openAuthInBrowser(mode: AuthMode): Promise<void> {
  const target = `${authWebBase()}/desktop/auth?mode=${mode}`;
  if (window.androDesktop?.openExternal) {
    await window.androDesktop.openExternal(target);
    return;
  }
  window.open(target, '_blank', 'noopener,noreferrer');
}

function BrowserAuthPanel({
  waiting,
  error,
  onStart,
  onCancel,
}: {
  waiting: WaitingState;
  error: string | null;
  onStart: (mode: AuthMode) => void;
  onCancel: () => void;
}) {
  if (waiting) {
    return (
      <div className="space-y-4 text-sm">
        <div className="flex items-start gap-3">
          <LoaderCircle className="mt-0.5 h-4 w-4 shrink-0 animate-spin text-primary" />
          <div>
            <p className="font-medium text-foreground">Complete sign-in in your browser</p>
            <p className="mt-1 text-muted-foreground leading-6">
              {waiting === 'sign-up'
                ? 'Finish creating your account in the browser tab we opened. Andro will unlock Trial when you return.'
                : 'Finish signing in in the browser tab we opened. Andro will unlock Trial when you return.'}
            </p>
          </div>
        </div>
        {error ? <p className="text-xs text-destructive">{error}</p> : null}
        <div className="flex flex-col gap-2 sm:flex-row">
          <Button type="button" className="flex-1" onClick={() => void onStart(waiting)}>
            Open browser again
          </Button>
          <Button type="button" variant="outline" className="flex-1" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-medium text-foreground">Hosted Trial</p>
        <p className="mt-1 text-sm leading-6 text-muted-foreground">
          Sign in or create an account in your browser — same flow as Cursor. We bring the session
          back here automatically.
        </p>
      </div>
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
      <div className="flex flex-col gap-2">
        <Button
          type="button"
          className="h-11 w-full gap-2 text-sm font-medium"
          onClick={() => void onStart('sign-in')}
        >
          Sign in
          <ExternalLink className="h-3.5 w-3.5 opacity-80" />
        </Button>
        <Button
          type="button"
          variant="outline"
          className="h-11 w-full gap-2 text-sm font-medium"
          onClick={() => void onStart('sign-up')}
        >
          Create account
          <ExternalLink className="h-3.5 w-3.5 opacity-80" />
        </Button>
      </div>
    </div>
  );
}

function ClerkTicketRedeem({
  waiting,
  setWaiting,
  error,
  setError,
  onStartBrowser,
}: {
  waiting: WaitingState;
  setWaiting: (next: WaitingState) => void;
  error: string | null;
  setError: (next: string | null) => void;
  onStartBrowser: (mode: AuthMode) => Promise<void>;
}) {
  // Redemption lives in DesktopAuthProvider at the app root, so a ticket that
  // arrives while this screen is unmounted still completes. This only mirrors
  // that state into the panel.
  const { status, error: ticketError } = useDesktopAuth();
  const busy = status === 'waiting' || status === 'redeeming';

  useEffect(() => {
    if (ticketError) {
      setError(ticketError);
    }
  }, [setError, ticketError]);

  return (
    <BrowserAuthPanel
      waiting={waiting ?? (busy ? 'sign-in' : null)}
      error={error ?? ticketError}
      onStart={onStartBrowser}
      onCancel={() => {
        setWaiting(null);
        setError(null);
      }}
    />
  );
}

export function AuthPage() {
  const { enterApp } = useAppMode();
  const [waiting, setWaiting] = useState<WaitingState>(null);
  const [error, setError] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const hasClerk = Boolean(import.meta.env.VITE_CLERK_PUBLISHABLE_KEY?.trim());
  const apiBase = andromediaApiBase();
  const hostedBase = hostedAndromediaApiBase();

  const startBrowserAuth = useCallback(async (mode: AuthMode) => {
    setError(null);
    setWaiting(mode);
    try {
      await openAuthInBrowser(mode);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not open the browser');
    }
  }, []);

  return (
    <div className="relative flex min-h-screen flex-col overflow-hidden bg-background text-foreground">
      <header className="relative z-10 flex items-center gap-3 px-6 py-5 sm:px-10">
        <AndroLogo />
      </header>

      <main className="relative z-10 mx-auto grid w-full max-w-5xl flex-1 items-center gap-10 px-6 pb-12 pt-2 sm:px-10 lg:grid-cols-[1.05fr_0.95fr] lg:gap-14 lg:pb-20">
        <section className="mx-auto w-full max-w-md lg:mx-0">
          <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-muted-foreground">
            Desktop
          </p>
          <h1 className="mt-3 text-balance text-3xl font-semibold leading-tight tracking-tight sm:text-4xl">
            Sign in for Trial, or stay local as Pro
          </h1>
          <p className="mt-3 text-pretty text-sm leading-6 text-muted-foreground">
            Trial opens your Andro account in the system browser, then returns here. Local Pro runs
            against your own API — full capability until paid plans ship.
          </p>

          <div className="mt-8 space-y-3">
            <Button
              type="button"
              className="h-11 w-full max-w-sm text-sm font-medium"
              onClick={() => enterApp('local_pro')}
            >
              Continue locally (Pro)
            </Button>
            <p className="max-w-sm text-[12px] leading-5 text-muted-foreground">
              No account required. Talks to{' '}
              <span className="font-mono text-foreground/80">
                {apiBase.includes('localhost') ? 'localhost:8083' : apiBase}
              </span>
              .
            </p>
          </div>

          <ul className="mt-10 hidden space-y-4 lg:block">
            <li className="flex gap-3">
              <span aria-hidden className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
              <div>
                <p className="text-sm font-medium">Hosted Trial</p>
                <p className="text-sm text-muted-foreground">
                  Browser sign-in → session returns to desktop → investigate with quota shown in-app.
                </p>
              </div>
            </li>
            <li className="flex gap-3">
              <span aria-hidden className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
              <div>
                <p className="text-sm font-medium">Local Pro</p>
                <p className="text-sm text-muted-foreground">
                  Unlimited local queries against your own core — switch modes anytime from Account.
                </p>
              </div>
            </li>
          </ul>
        </section>

        <section className="mx-auto w-full max-w-md lg:mx-0 lg:justify-self-end">
          <div className="rounded-lg border border-border bg-card p-5 sm:p-7">
            {!hasClerk ? (
              <div className="space-y-3 text-sm leading-6 text-muted-foreground">
                <p className="font-medium text-foreground">Trial sign-in unavailable</p>
                <p>
                  Set{' '}
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-foreground">
                    VITE_CLERK_PUBLISHABLE_KEY
                  </code>{' '}
                  in{' '}
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-foreground">
                    andro/.env.local
                  </code>
                  , then rebuild. Local Pro still works above.
                </p>
              </div>
            ) : (
              <ClerkTicketRedeem
                waiting={waiting}
                setWaiting={setWaiting}
                error={error}
                setError={setError}
                onStartBrowser={startBrowserAuth}
              />
            )}
          </div>

          <div className="mt-4 text-center">
            <button
              type="button"
              className="font-mono text-[11px] text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
              onClick={() => setShowAdvanced((v) => !v)}
            >
              {showAdvanced ? 'Hide API endpoints' : 'Show API endpoints'}
            </button>
            {showAdvanced ? (
              <p className="mt-2 break-all text-left font-mono text-[10px] leading-5 text-muted-foreground">
                Auth web: {authWebBase()}
                <br />
                Local Pro: {apiBase}
                <br />
                Trial API: {hostedBase}
              </p>
            ) : null}
          </div>
        </section>
      </main>
    </div>
  );
}
