import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useAuth, useUser } from '@clerk/clerk-react';
import { useAppMode } from '@/hooks/useAppMode';
import {
  fetchTrialConfig,
  fetchTrialStatus,
  setRequestAuth,
  startTrialSession,
  type TrialStatus,
} from '@/lib/andromediaApi';
import { trialIdFromClerkUserId } from '@/lib/trial-id';

export interface TrialSessionValue {
  loading: boolean;
  /** Backend reports trial.enabled on the configured API. */
  backendTrialEnabled: boolean;
  /** User is in Trial product mode with a ready session. */
  isTrialReady: boolean;
  /** User chose Local Pro. */
  isLocalPro: boolean;
  trialId: string | null;
  serviceName: string;
  remaining: number | null;
  maxQueries: number | null;
  error: string | null;
  userEmail: string | null;
  getAccessToken: () => Promise<string | null>;
  refreshStatus: () => Promise<void>;
  /** Ready to render AppShell (entered + session ok for mode). */
  canUseApp: boolean;
}

const TrialSessionContext = createContext<TrialSessionValue | null>(null);

const clerkConfigured = () => Boolean(import.meta.env.VITE_CLERK_PUBLISHABLE_KEY?.trim());

function LocalOnlySessionProvider({ children }: { children: ReactNode }) {
  const { mode, hasEnteredApp } = useAppMode();

  useEffect(() => {
    setRequestAuth({
      mode: 'local_pro',
      trialId: null,
      getAccessToken: async () => null,
    });
  }, []);

  const value = useMemo<TrialSessionValue>(() => {
    const ready = hasEnteredApp && mode === 'local_pro';
    return {
      loading: false,
      backendTrialEnabled: false,
      isTrialReady: false,
      isLocalPro: ready,
      trialId: null,
      serviceName: 'local',
      remaining: null,
      maxQueries: null,
      error: hasEnteredApp && mode === 'trial'
        ? 'Clerk is not configured. Set VITE_CLERK_PUBLISHABLE_KEY or use Local Pro.'
        : null,
      userEmail: null,
      getAccessToken: async () => null,
      refreshStatus: async () => undefined,
      canUseApp: ready,
    };
  }, [hasEnteredApp, mode]);

  return (
    <TrialSessionContext.Provider value={value}>{children}</TrialSessionContext.Provider>
  );
}

function ClerkTrialSessionProvider({ children }: { children: ReactNode }) {
  const { mode, hasEnteredApp } = useAppMode();
  const { isLoaded, isSignedIn, userId, getToken } = useAuth();
  const { user } = useUser();
  const [loading, setLoading] = useState(true);
  const [backendTrialEnabled, setBackendTrialEnabled] = useState(false);
  const [trialId, setTrialId] = useState<string | null>(null);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [maxQueries, setMaxQueries] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sessionReady, setSessionReady] = useState(false);

  const getAccessToken = useCallback(async () => {
    try {
      return (await getToken()) ?? null;
    } catch {
      return null;
    }
  }, [getToken]);

  const applyStatus = useCallback((status: TrialStatus) => {
    setTrialId(status.trialId);
    setRemaining(status.remaining);
    setMaxQueries(status.maxQueries);
    setSessionReady(true);
  }, []);

  const refreshStatus = useCallback(async () => {
    if (mode !== 'trial' || !trialId) {
      return;
    }
    try {
      const token = await getAccessToken();
      const status = await fetchTrialStatus(trialId, token);
      setRemaining(status.remaining);
      setMaxQueries(status.maxQueries);
    } catch {
      // ignore transient
    }
  }, [getAccessToken, mode, trialId]);

  useEffect(() => {
    if (mode === 'trial' && trialId && sessionReady) {
      setRequestAuth({
        mode: 'trial',
        trialId,
        getAccessToken,
      });
    } else {
      setRequestAuth({
        mode: 'local_pro',
        trialId: null,
        getAccessToken: async () => null,
      });
    }
  }, [getAccessToken, mode, sessionReady, trialId]);

  useEffect(() => {
    let cancelled = false;

    const bootstrap = async () => {
      if (!hasEnteredApp) {
        setLoading(false);
        setSessionReady(false);
        setError(null);
        return;
      }

      if (mode === 'local_pro') {
        setLoading(false);
        setBackendTrialEnabled(false);
        setTrialId(null);
        setRemaining(null);
        setMaxQueries(null);
        setSessionReady(true);
        setError(null);
        return;
      }

      if (!isLoaded) {
        return;
      }

      setLoading(true);
      setError(null);

      try {
        const config = await fetchTrialConfig();
        if (cancelled) {
          return;
        }
        setBackendTrialEnabled(config.enabled);

        if (!config.enabled) {
          setSessionReady(true);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          setError(
            'This API is not running in trial mode. Use Local Pro against localhost, or point VITE_ANDROMEDIA_API_URL at a trial-enabled host.',
          );
          return;
        }

        if (!isSignedIn || !userId) {
          setSessionReady(false);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          setError('Sign in to start your trial.');
          return;
        }

        const id = trialIdFromClerkUserId(userId);
        const token = await getAccessToken();
        if (!token) {
          throw new Error('Could not get Clerk session token. Refresh and try again.');
        }
        const status = await startTrialSession(id, token);
        if (cancelled) {
          return;
        }
        applyStatus(status);
        setError(null);
      } catch (err) {
        if (!cancelled) {
          setSessionReady(false);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          setError(err instanceof Error ? err.message : 'Failed to start trial');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void bootstrap();
    return () => {
      cancelled = true;
    };
  }, [
    applyStatus,
    getAccessToken,
    hasEnteredApp,
    isLoaded,
    isSignedIn,
    mode,
    userId,
  ]);

  const isTrialReady = mode === 'trial' && sessionReady && Boolean(trialId);
  const isLocalPro = mode === 'local_pro' && hasEnteredApp && sessionReady;
  const serviceName = isTrialReady && trialId ? trialId : 'local';
  const canUseApp =
    hasEnteredApp &&
    !loading &&
    (isLocalPro ||
      isTrialReady ||
      (mode === 'trial' && sessionReady && !backendTrialEnabled));

  const value = useMemo<TrialSessionValue>(
    () => ({
      loading: hasEnteredApp && (mode === 'trial' ? !isLoaded || loading : loading),
      backendTrialEnabled,
      isTrialReady,
      isLocalPro,
      trialId,
      serviceName,
      remaining,
      maxQueries,
      error,
      userEmail: user?.primaryEmailAddress?.emailAddress ?? null,
      getAccessToken,
      refreshStatus,
      canUseApp,
    }),
    [
      backendTrialEnabled,
      canUseApp,
      error,
      getAccessToken,
      hasEnteredApp,
      isLoaded,
      isLocalPro,
      isTrialReady,
      loading,
      maxQueries,
      mode,
      refreshStatus,
      remaining,
      serviceName,
      trialId,
      user?.primaryEmailAddress?.emailAddress,
    ],
  );

  return (
    <TrialSessionContext.Provider value={value}>{children}</TrialSessionContext.Provider>
  );
}

export function TrialSessionProvider({ children }: { children: ReactNode }) {
  if (!clerkConfigured()) {
    return <LocalOnlySessionProvider>{children}</LocalOnlySessionProvider>;
  }
  return <ClerkTrialSessionProvider>{children}</ClerkTrialSessionProvider>;
}

export function useTrialSession(): TrialSessionValue {
  const ctx = useContext(TrialSessionContext);
  if (!ctx) {
    throw new Error('useTrialSession must be used within TrialSessionProvider');
  }
  return ctx;
}
