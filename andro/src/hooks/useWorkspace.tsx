import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  fetchWorkspaces,
  getActiveWorkspace,
  setActiveWorkspace,
  type WorkspaceSummary,
} from '@/lib/andromediaApi';
import { useAppMode } from '@/hooks/useAppMode';
import { useTrialSession } from '@/hooks/useTrialSession';

/**
 * Workspace selection.
 *
 * A "workspace" is one indexed project (the backend calls it a `service`). The
 * selection is stored in localStorage and pushed into the API client via
 * `setActiveWorkspace`, so every request picks it up without prop-drilling.
 *
 * In trial mode the selection is meaningless — a trial can only ever reach its
 * own workspace — so `workspaces` is left empty and `active` reports the trial
 * id, keeping the UI honest about what is reachable.
 */

const STORAGE_KEY = 'andro.workspace';

interface WorkspaceContextValue {
  workspaces: WorkspaceSummary[];
  active: string | null;
  loading: boolean;
  error: string | null;
  /** True when the switch is a no-op because trial mode pins the workspace. */
  locked: boolean;
  select: (service: string) => void;
  refresh: () => Promise<void>;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

function readStored(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeStored(value: string | null): void {
  try {
    if (value) {
      window.localStorage.setItem(STORAGE_KEY, value);
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Storage unavailable (private mode); selection simply won't persist.
  }
}

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const { mode } = useAppMode();
  const { serviceName } = useTrialSession();
  const locked = mode === 'trial';

  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [active, setActive] = useState<string | null>(() =>
    locked ? null : readStored() ?? getActiveWorkspace(),
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (locked) {
      // Nothing to enumerate: the trial's own workspace is the only one.
      setWorkspaces([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const rows = await fetchWorkspaces();
      setWorkspaces(rows);
      // Resolve the selection outside the state updater: writing localStorage and
      // the API client from inside one would run twice under StrictMode, since
      // React intentionally double-invokes updaters.
      const stored = readStored();
      const stillExists = stored && rows.some((row) => row.service === stored);
      const next = stillExists
        ? stored
        : (rows.find((row) => row.exists)?.service ?? null);
      setActive(next);
      writeStored(next);
      setActiveWorkspace(next);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Failed to load workspaces');
    } finally {
      setLoading(false);
    }
  }, [locked]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Re-fetch when the app mode flips (trial <-> local pro), since the reachable
  // set of workspaces changes entirely.
  useEffect(() => {
    if (!locked) {
      void refresh();
    }
  }, [locked, refresh]);

  const select = useCallback(
    (service: string) => {
      if (locked) {
        return;
      }
      setActive(service);
      writeStored(service);
      setActiveWorkspace(service);
    },
    [locked],
  );

  const value = useMemo<WorkspaceContextValue>(
    () => ({
      workspaces,
      active: locked ? serviceName : active,
      loading,
      error,
      locked,
      select,
      refresh,
    }),
    [workspaces, active, loading, error, locked, select, refresh, serviceName],
  );

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace(): WorkspaceContextValue {
  const ctx = useContext(WorkspaceContext);
  if (!ctx) {
    throw new Error('useWorkspace must be used within WorkspaceProvider');
  }
  return ctx;
}
