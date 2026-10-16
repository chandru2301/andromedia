import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useClerk, useSignIn } from '@clerk/clerk-react';
import { useAppMode } from '@/hooks/useAppMode';

/**
 * Browser -> desktop sign-in handoff.
 *
 * The landing site mints a short-lived Clerk ticket and hands it back through
 * andro://auth/callback?ticket=… . This lives at the app root rather than on
 * the auth screen because the deep link can land at any moment: the listener
 * used to be scoped to <AuthPage>, so a ticket arriving while the user was
 * anywhere else was delivered to nobody and silently lost.
 */

export type DesktopAuthStatus = 'idle' | 'waiting' | 'redeeming' | 'error';

interface DesktopAuthState {
  status: DesktopAuthStatus;
  error: string | null;
  clearError: () => void;
}

const DesktopAuthContext = createContext<DesktopAuthState>({
  status: 'idle',
  error: null,
  clearError: () => undefined,
});

export function useDesktopAuth(): DesktopAuthState {
  return useContext(DesktopAuthContext);
}

function parseTicketFromCallback(url: string): string | null {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'andro:') {
      return null;
    }
    const ticket =
      parsed.searchParams.get('ticket') ??
      new URLSearchParams(parsed.hash.replace(/^#/, '')).get('ticket');
    return ticket?.trim() || null;
  } catch {
    return null;
  }
}

interface BridgeProps {
  onStatus: (status: DesktopAuthStatus) => void;
  onError: (error: string | null) => void;
}

/** Only mounted when Clerk is configured — these hooks require ClerkProvider. */
function TicketBridge({ onStatus, onError }: BridgeProps) {
  const { isLoaded, signIn } = useSignIn();
  const { setActive } = useClerk();
  const { enterApp } = useAppMode();

  // Held when a ticket arrives before Clerk finished loading. Previously such a
  // ticket was dropped with no error and no retry.
  const bufferedTicket = useRef<string | null>(null);
  const redeeming = useRef(false);
  // A ticket reaches us twice: once via the live event, once from the queue we
  // drain on mount. Clerk tickets are single use, so replaying one produces a
  // bogus "invalid ticket" error after an otherwise successful sign-in.
  const seenTickets = useRef<Set<string>>(new Set());

  const redeem = useCallback(
    async (ticket: string) => {
      if (redeeming.current) {
        return;
      }
      redeeming.current = true;
      onStatus('redeeming');
      onError(null);
      try {
        const result = await signIn!.create({ strategy: 'ticket', ticket });
        if (result.status === 'complete' && result.createdSessionId) {
          await setActive({ session: result.createdSessionId });
          enterApp('trial');
          onStatus('idle');
          return;
        }
        throw new Error('Sign-in ticket was not completed. Try again from the browser.');
      } catch (caught) {
        // Logged as well as surfaced: the auth screen may not be mounted when a
        // ticket fails, and a silent failure here is what made the broken
        // handoff so hard to see.
        console.warn('[andro] desktop sign-in ticket failed', caught);
        onStatus('error');
        onError(
          caught instanceof Error ? caught.message : 'Failed to complete desktop sign-in',
        );
      } finally {
        redeeming.current = false;
      }
    },
    [enterApp, onError, onStatus, setActive, signIn],
  );

  const handleUrl = useCallback(
    (url: string) => {
      const ticket = parseTicketFromCallback(url);
      if (!ticket) {
        onStatus('error');
        onError('Invalid auth callback. Open Sign in again.');
        return;
      }
      if (!isLoaded || !signIn) {
        // Buffer rather than drop; the effect below redeems once Clerk is ready.
        bufferedTicket.current = ticket;
        onStatus('waiting');
        return;
      }

      if (seenTickets.current.has(ticket)) {
        return;
      }
      seenTickets.current.add(ticket);
      // Clear the main-process queue so the mount-time drain cannot replay it.
      void window.androDesktop?.consumeAuthCallback?.().catch(() => undefined);
      void redeem(ticket);
    },
    [isLoaded, onError, onStatus, redeem, signIn],
  );

  // Subscribe, and claim anything that arrived before this mounted.
  useEffect(() => {
    const bridge = window.androDesktop;
    if (!bridge) {
      return undefined;
    }
    const unsubscribe = bridge.onAuthCallback(handleUrl);
    void bridge
      .consumeAuthCallback?.()
      .then((url) => {
        if (url) {
          handleUrl(url);
        }
      })
      .catch(() => undefined);
    return unsubscribe;
  }, [handleUrl]);

  // Flush a ticket that arrived while Clerk was still loading.
  useEffect(() => {
    if (isLoaded && signIn && bufferedTicket.current) {
      const ticket = bufferedTicket.current;
      bufferedTicket.current = null;
      void redeem(ticket);
    }
  }, [isLoaded, redeem, signIn]);

  return null;
}

export function DesktopAuthProvider({
  children,
  enabled,
}: {
  children: ReactNode;
  enabled: boolean;
}) {
  const [status, setStatus] = useState<DesktopAuthStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const clearError = useCallback(() => {
    setError(null);
    setStatus('idle');
  }, []);

  return (
    <DesktopAuthContext.Provider value={{ status, error, clearError }}>
      {enabled ? <TicketBridge onStatus={setStatus} onError={setError} /> : null}
      {children}
    </DesktopAuthContext.Provider>
  );
}
