"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useAuth } from "@clerk/nextjs";

import {
  fetchTrialConfig,
  fetchTrialStatus,
  startTrialSession,
} from "@/lib/api/andromedia";
import { trialIdFromClerkUserId } from "@/lib/trial-id";

export interface TrialContextValue {
  loading: boolean;
  /** Backend has trial mode on (hosted). Never use service=local against this API. */
  trialMode: boolean;
  isTrial: boolean;
  trialId: string | null;
  /** Empty while trial session is not ready in trialMode. */
  serviceName: string;
  remaining: number | null;
  maxQueries: number | null;
  error: string | null;
  getAccessToken: () => Promise<string | null>;
  refreshStatus: () => Promise<void>;
}

const TrialContext = createContext<TrialContextValue | null>(null);

export function TrialProvider({ children }: { children: ReactNode }) {
  const { isLoaded, isSignedIn, userId, getToken } = useAuth();
  const isDevMode = process.env.NEXT_PUBLIC_ANDRO_DEV_MODE === "true";
  const authReady = isDevMode || isLoaded;
  const [loading, setLoading] = useState(true);
  const [trialMode, setTrialMode] = useState(false);
  const [isTrial, setIsTrial] = useState(false);
  const [trialId, setTrialId] = useState<string | null>(null);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [maxQueries, setMaxQueries] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const getAccessToken = useCallback(async () => {
    try {
      return (await getToken()) ?? null;
    } catch {
      return null;
    }
  }, [getToken]);

  const refreshStatus = useCallback(async () => {
    if (!trialId || !isTrial) {
      return;
    }
    try {
      const token = await getAccessToken();
      const status = await fetchTrialStatus(trialId, token);
      setRemaining(status.remaining);
      setMaxQueries(status.maxQueries);
    } catch {
      // Ignore transient status errors; investigate responses still update quota.
    }
  }, [getAccessToken, isTrial, trialId]);

  useEffect(() => {
    let cancelled = false;

    const bootstrap = async () => {
      if (!authReady) {
        return;
      }

      setLoading(true);
      setError(null);

      try {
        const config = await fetchTrialConfig();
        if (cancelled) {
          return;
        }
        setTrialMode(config.enabled);

        if (!config.enabled) {
          setIsTrial(false);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          return;
        }

        if (!isSignedIn || !userId) {
          setIsTrial(false);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          setError("Sign in to start your trial.");
          return;
        }

        const id = trialIdFromClerkUserId(userId);
        const token = await getAccessToken();
        if (!token) {
          throw new Error("Could not get Clerk session token. Refresh and try again.");
        }
        const status = await startTrialSession(id, token);
        if (cancelled) {
          return;
        }
        setIsTrial(true);
        setTrialId(status.trialId);
        setRemaining(status.remaining);
        setMaxQueries(status.maxQueries);
        setError(null);
      } catch (err) {
        if (!cancelled) {
          setIsTrial(false);
          setTrialId(null);
          setRemaining(null);
          setMaxQueries(null);
          setError(err instanceof Error ? err.message : "Failed to start trial");
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
  }, [authReady, getAccessToken, isSignedIn, userId]);

  const serviceName = useMemo(() => {
    if (trialMode) {
      return isTrial && trialId ? trialId : "";
    }
    return "local";
  }, [isTrial, trialId, trialMode]);

  const value = useMemo<TrialContextValue>(
    () => ({
      loading: !authReady || loading,
      trialMode,
      isTrial,
      trialId,
      serviceName,
      remaining,
      maxQueries,
      error,
      getAccessToken,
      refreshStatus,
    }),
    [
      authReady,
      error,
      getAccessToken,
      isTrial,
      loading,
      maxQueries,
      refreshStatus,
      remaining,
      serviceName,
      trialId,
      trialMode,
    ],
  );

  return <TrialContext.Provider value={value}>{children}</TrialContext.Provider>;
}

export function useTrial(): TrialContextValue {
  const context = useContext(TrialContext);
  if (!context) {
    throw new Error("useTrial must be used within TrialProvider");
  }
  return context;
}
