import { useEffect, useRef, useState } from 'react';
import { useAuth } from '@clerk/clerk-react';
import { Check, ChevronDown, LogOut } from 'lucide-react';
import { useAppMode } from '@/hooks/useAppMode';
import { useChatProvider } from '@/hooks/useChatProvider';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { useTrialSession } from '@/hooks/useTrialSession';

const FALLBACK_PROVIDERS = [
  { id: 'groq', label: 'Groq', configured: true, apiKeyConfigured: true, model: '', active: true },
  { id: 'deepseek', label: 'DeepSeek', configured: false, apiKeyConfigured: false, model: '', active: false },
  { id: 'openrouter', label: 'OpenRouter', configured: false, apiKeyConfigured: false, model: '', active: false },
  { id: 'ollama', label: 'Ollama (local)', configured: false, apiKeyConfigured: false, model: '', active: false },
] as const;

interface ProfileMenuProps {
  /** 'sidebar' is a full-width labeled row; 'composer' is a compact inline pill for the chat input toolbar. */
  variant?: 'sidebar' | 'composer';
}

function ClerkSignOutItem({ onSignedOut }: { onSignedOut: () => void }) {
  const { signOut } = useAuth();
  return (
    <button
      type="button"
      role="menuitem"
      className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-xs text-foreground hover:bg-muted"
      onClick={() => {
        void (async () => {
          try {
            await signOut();
          } catch {
            // ignore
          }
          onSignedOut();
        })();
      }}
    >
      <LogOut className="h-3 w-3" />
      Sign out
    </button>
  );
}

export function ProfileMenu({ variant = 'sidebar' }: ProfileMenuProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const { mode, enterApp, exitToAuth } = useAppMode();
  const { serviceName, remaining, maxQueries, userEmail, isTrialReady } = useTrialSession();
  const { selection, loading, error, select } = useChatProvider(serviceName);
  const overview = useIndexOverview(serviceName);
  const hasClerk = Boolean(import.meta.env.VITE_CLERK_PUBLISHABLE_KEY?.trim());

  const isZohoIndexed = overview.raw?.sourceTypes.includes('ZOHO') ?? false;

  useEffect(() => {
    if (!open) {
      return;
    }
    const onClickAway = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClickAway);
    document.addEventListener('keydown', onEscape);
    return () => {
      document.removeEventListener('mousedown', onClickAway);
      document.removeEventListener('keydown', onEscape);
    };
  }, [open]);

  const providers = selection?.providers ?? FALLBACK_PROVIDERS;
  const activeProvider = selection?.activeProvider ?? 'groq';
  const activeLabel =
    providers.find((provider) => provider.id === activeProvider)?.label ?? 'Model';

  const isComposer = variant === 'composer';
  const modeBadge =
    mode === 'trial'
      ? remaining != null
        ? `Trial · ${remaining}${maxQueries != null ? `/${maxQueries}` : ''} left`
        : 'Trial'
      : 'Local Pro';

  return (
    <div className="relative" ref={containerRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className={
          isComposer
            ? 'flex h-7 cursor-pointer items-center gap-1.5 rounded-md border border-transparent px-2 text-[12px] text-muted-foreground transition-colors hover:border-border hover:bg-muted/60 hover:text-foreground'
            : 'flex h-8 w-full cursor-pointer items-center gap-2 rounded-md px-2.5 text-[13px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground'
        }
      >
        {!isComposer && <span className="min-w-0 flex-1 truncate text-left">Account</span>}
        <span className={isComposer ? 'max-w-[10rem] truncate' : 'max-w-[7rem] truncate text-[11px]'}>
          {isComposer ? modeBadge : activeLabel}
        </span>
        <ChevronDown className="h-3 w-3 shrink-0" />
      </button>

      {open && (
        <div
          role="menu"
          className={
            isComposer
              ? 'absolute bottom-full left-0 z-50 mb-2 w-64 space-y-3 rounded-lg border border-border bg-background p-3 shadow-lg'
              : 'absolute bottom-full left-0 z-50 mb-2 w-64 space-y-3 rounded-lg border border-border bg-background p-3'
          }
        >
          <section>
            <h4 className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.14em] text-muted-foreground">
              Mode
            </h4>
            <p className="px-2 text-xs text-foreground">{modeBadge}</p>
            {userEmail ? (
              <p className="mt-1 truncate px-2 text-[11px] text-muted-foreground">{userEmail}</p>
            ) : null}
            <div className="mt-2 space-y-1">
              {mode !== 'local_pro' ? (
                <button
                  type="button"
                  role="menuitem"
                  className="flex w-full rounded-md px-2 py-1.5 text-left text-xs text-foreground hover:bg-muted"
                  onClick={() => {
                    setOpen(false);
                    enterApp('local_pro');
                  }}
                >
                  Switch to Local Pro
                </button>
              ) : (
                <button
                  type="button"
                  role="menuitem"
                  className="flex w-full rounded-md px-2 py-1.5 text-left text-xs text-foreground hover:bg-muted"
                  onClick={() => {
                    setOpen(false);
                    exitToAuth();
                  }}
                >
                  Switch to Trial (sign in)
                </button>
              )}
              {hasClerk && (isTrialReady || mode === 'trial') ? (
                <ClerkSignOutItem
                  onSignedOut={() => {
                    setOpen(false);
                    exitToAuth();
                  }}
                />
              ) : mode === 'local_pro' ? (
                <button
                  type="button"
                  role="menuitem"
                  className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-xs text-foreground hover:bg-muted"
                  onClick={() => {
                    setOpen(false);
                    exitToAuth();
                  }}
                >
                  <LogOut className="h-3 w-3" />
                  Back to welcome
                </button>
              ) : null}
            </div>
          </section>

          {mode === 'local_pro' ? (
            <section>
              <h4 className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.14em] text-muted-foreground">
                Model
              </h4>
              <div className="space-y-1">
                {providers.map((provider) => (
                  <button
                    key={provider.id}
                    type="button"
                    role="menuitemradio"
                    aria-checked={provider.id === activeProvider}
                    disabled={!provider.configured || loading}
                    onClick={() => void select(provider.id)}
                    className={`flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-xs transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                      provider.id === activeProvider
                        ? 'bg-primary/10 text-primary'
                        : 'text-foreground hover:bg-muted'
                    }`}
                  >
                    <span>
                      {provider.label}
                      {!provider.configured && (
                        <span className="ml-1 text-[10px] text-muted-foreground">(not configured)</span>
                      )}
                    </span>
                    {provider.id === activeProvider && <Check className="h-3 w-3" />}
                  </button>
                ))}
              </div>
              {error && <p className="mt-1.5 text-[10px] text-destructive">{error}</p>}
            </section>
          ) : (
            <p className="px-2 text-[11px] text-muted-foreground">
              Trial uses the hosted chat model. Switch to Local Pro to change providers.
            </p>
          )}

          <section className="border-t border-border pt-2.5">
            <h4 className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.14em] text-muted-foreground">
              Connections
            </h4>
            <div className="flex items-center justify-between rounded-md px-2 py-1.5 text-xs text-foreground">
              <span>Zoho CRM</span>
              <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <span
                  className={`h-1.5 w-1.5 rounded-full ${isZohoIndexed ? 'bg-primary' : 'bg-warning'}`}
                />
                {overview.loading ? 'checking…' : isZohoIndexed ? 'connected' : 'not indexed'}
              </span>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
