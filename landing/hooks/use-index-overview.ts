import { useCallback, useEffect, useState } from "react";
import {
  deriveIndexFreshness,
  fetchIndexOverview,
  formatIndexSize,
  formatRelativeSync,
  type IndexFreshness,
  type IndexOverviewResponse,
} from "@/lib/api/andromedia";

const REFRESH_MS = 30_000;

export interface IndexOverviewView {
  totalChunks: number;
  sourcesConnected: number;
  lastSync: string;
  freshness: IndexFreshness;
  indexSize: string;
  exists: boolean;
  loading: boolean;
  error: string | null;
  raw: IndexOverviewResponse | null;
  refresh: () => Promise<void>;
}

const emptyView = {
  totalChunks: 0,
  sourcesConnected: 0,
  lastSync: 'never',
  freshness: 'unknown' as IndexFreshness,
  indexSize: '0 B',
  exists: false,
};

type CacheEntry = {
  raw: IndexOverviewResponse | null;
  error: string | null;
  loading: boolean;
  fetchedAt: number;
};

type TokenGetter = () => Promise<string | null>;

const cacheByService = new Map<string, CacheEntry>();
const listenersByService = new Map<string, Set<() => void>>();
const inflightByService = new Map<string, Promise<void>>();
const subscriberCountByService = new Map<string, number>();
const intervalByService = new Map<string, number>();
const trialIdByService = new Map<string, string | undefined>();
const tokenGetterByService = new Map<string, TokenGetter | undefined>();

function ensurePolling(service: string, trialId?: string, getToken?: TokenGetter) {
  if (!service) {
    return;
  }
  trialIdByService.set(service, trialId);
  tokenGetterByService.set(service, getToken);
  if (intervalByService.has(service)) {
    return;
  }

  void loadOverview(service, false, trialId, getToken);

  intervalByService.set(
    service,
    window.setInterval(() => {
      if (document.visibilityState !== "visible") {
        return;
      }
      void loadOverview(
        service,
        true,
        trialIdByService.get(service),
        tokenGetterByService.get(service),
      );
    }, REFRESH_MS),
  );
}

function releasePolling(service: string) {
  const count = (subscriberCountByService.get(service) ?? 1) - 1;
  if (count <= 0) {
    subscriberCountByService.delete(service);
    trialIdByService.delete(service);
    tokenGetterByService.delete(service);
    const interval = intervalByService.get(service);
    if (interval) {
      window.clearInterval(interval);
      intervalByService.delete(service);
    }
    return;
  }
  subscriberCountByService.set(service, count);
}

function subscribe(service: string, listener: () => void) {
  let listeners = listenersByService.get(service);
  if (!listeners) {
    listeners = new Set();
    listenersByService.set(service, listeners);
  }
  listeners.add(listener);
  return () => {
    listeners?.delete(listener);
  };
}

function notify(service: string) {
  listenersByService.get(service)?.forEach((listener) => listener());
}

async function loadOverview(
  service: string,
  force = false,
  trialId?: string,
  getToken?: TokenGetter,
): Promise<void> {
  if (!service) {
    return;
  }
  const existing = inflightByService.get(service);
  if (existing && !force) {
    return existing;
  }

  const cached = cacheByService.get(service);
  if (!cached) {
    cacheByService.set(service, {
      raw: null,
      error: null,
      loading: true,
      fetchedAt: 0,
    });
    notify(service);
  } else if (!force && cached.raw && Date.now() - cached.fetchedAt < REFRESH_MS) {
    return;
  }

  const request = (async () => {
    try {
      const token = getToken ? await getToken() : null;
      const overview = await fetchIndexOverview(service, trialId, token);
      cacheByService.set(service, {
        raw: overview,
        error: null,
        loading: false,
        fetchedAt: Date.now(),
      });
    } catch (err) {
      cacheByService.set(service, {
        raw: null,
        error: err instanceof Error ? err.message : 'Failed to load index overview',
        loading: false,
        fetchedAt: Date.now(),
      });
    } finally {
      inflightByService.delete(service);
      notify(service);
    }
  })();

  inflightByService.set(service, request);
  return request;
}

export function useIndexOverview(
  service = 'local',
  trialId?: string | null,
  getAccessToken?: TokenGetter,
): IndexOverviewView {
  const resolvedTrialId = trialId ?? undefined;
  const [, setTick] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  const refresh = useCallback(async () => {
    await loadOverview(service, true, resolvedTrialId, getAccessToken);
    setNow(Date.now());
  }, [getAccessToken, service, resolvedTrialId]);

  useEffect(() => {
    if (!service) {
      return;
    }
    return subscribe(service, () => {
      setTick((value) => value + 1);
      setNow(Date.now());
    });
  }, [service]);

  useEffect(() => {
    if (!service) {
      return;
    }
    subscriberCountByService.set(
      service,
      (subscriberCountByService.get(service) ?? 0) + 1,
    );
    ensurePolling(service, resolvedTrialId, getAccessToken);

    const onFocus = () => {
      void loadOverview(
        service,
        true,
        trialIdByService.get(service),
        tokenGetterByService.get(service),
      );
    };
    window.addEventListener('focus', onFocus);

    return () => {
      releasePolling(service);
      window.removeEventListener('focus', onFocus);
    };
  }, [getAccessToken, service, resolvedTrialId]);

  if (!service) {
    return {
      ...emptyView,
      loading: true,
      error: null,
      raw: null,
      refresh,
    };
  }

  const cached = cacheByService.get(service);
  const raw = cached?.raw ?? null;
  const loading = cached?.loading ?? true;
  const error = cached?.error ?? null;

  if (!raw) {
    return {
      ...emptyView,
      loading,
      error,
      raw: null,
      refresh,
    };
  }

  return {
    totalChunks: raw.totalChunks,
    sourcesConnected: raw.sourcesConnected,
    lastSync: formatRelativeSync(raw.lastSyncAt, now),
    freshness: deriveIndexFreshness(raw.lastSyncAt, now),
    indexSize: formatIndexSize(raw.indexSizeBytes),
    exists: raw.exists,
    loading,
    error,
    raw,
    refresh,
  };
}
