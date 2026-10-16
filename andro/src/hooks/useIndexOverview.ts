import { useCallback, useEffect, useState } from 'react';
import {
  fetchIndexOverview,
  formatIndexSize,
  formatRelativeSync,
  resolveService,
  type IndexOverviewResponse,
} from '@/lib/andromediaApi';
import { useWorkspace } from '@/hooks/useWorkspace';

const REFRESH_MS = 30_000;

export interface IndexOverviewView {
  totalChunks: number;
  sourcesConnected: number;
  lastSync: string;
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
  indexSize: '0 B',
  exists: false,
};

type CacheEntry = {
  raw: IndexOverviewResponse | null;
  error: string | null;
  loading: boolean;
  fetchedAt: number;
};

const cacheByService = new Map<string, CacheEntry>();
const listenersByService = new Map<string, Set<() => void>>();
const inflightByService = new Map<string, Promise<void>>();

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

async function loadOverview(service: string, force = false): Promise<void> {
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
      const overview = await fetchIndexOverview(service);
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

export function useIndexOverview(service?: string): IndexOverviewView {
  // Subscribing to the workspace context is what makes this hook re-render when
  // the user switches workspace. Without it, `resolveService` would only be
  // recomputed on some unrelated re-render, so the sidebar would keep showing the
  // previous workspace's chunk counts after a switch.
  const { active } = useWorkspace();
  const resolved = resolveService(service ?? active ?? undefined);
  const [, setTick] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  const refresh = useCallback(async () => {
    await loadOverview(resolved, true);
    setNow(Date.now());
  }, [resolved]);

  useEffect(() => {
    return subscribe(resolved, () => {
      setTick((value) => value + 1);
      setNow(Date.now());
    });
  }, [resolved]);

  useEffect(() => {
    void loadOverview(resolved);
    const interval = window.setInterval(() => {
      void loadOverview(resolved, true);
    }, REFRESH_MS);

    const onFocus = () => {
      void loadOverview(resolved, true);
    };
    window.addEventListener('focus', onFocus);

    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
    };
  }, [resolved]);

  const cached = cacheByService.get(resolved);
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
    indexSize: formatIndexSize(raw.indexSizeBytes),
    exists: raw.exists,
    loading,
    error,
    raw,
    refresh,
  };
}
