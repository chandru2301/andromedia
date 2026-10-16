import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export type AppMode = 'trial' | 'local_pro';

const STORAGE_KEY = 'andro.appMode';
const ENTERED_KEY = 'andro.appEntered';

interface AppModeContextValue {
  /** Persisted product mode: Trial (hosted Clerk) or Local Pro (self-host). */
  mode: AppMode;
  setMode: (mode: AppMode) => void;
  /**
   * True after the user has chosen Sign in/up (trial) or Continue locally.
   * Cleared on explicit sign-out from Trial so AuthPage shows again.
   */
  hasEnteredApp: boolean;
  enterApp: (mode: AppMode) => void;
  exitToAuth: () => void;
}

const AppModeContext = createContext<AppModeContextValue | null>(null);

function readStoredMode(): AppMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === 'trial' || raw === 'local_pro') {
      return raw;
    }
  } catch {
    // ignore
  }
  return 'local_pro';
}

function readEntered(): boolean {
  try {
    return localStorage.getItem(ENTERED_KEY) === '1';
  } catch {
    return false;
  }
}

export function AppModeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<AppMode>(() => readStoredMode());
  const [hasEnteredApp, setHasEnteredApp] = useState(() => readEntered());

  const setMode = useCallback((next: AppMode) => {
    setModeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // ignore
    }
  }, []);

  const enterApp = useCallback(
    (next: AppMode) => {
      setMode(next);
      setHasEnteredApp(true);
      try {
        localStorage.setItem(ENTERED_KEY, '1');
      } catch {
        // ignore
      }
    },
    [setMode],
  );

  const exitToAuth = useCallback(() => {
    setHasEnteredApp(false);
    try {
      localStorage.removeItem(ENTERED_KEY);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore
    }
  }, [mode]);

  const value = useMemo(
    () => ({ mode, setMode, hasEnteredApp, enterApp, exitToAuth }),
    [mode, setMode, hasEnteredApp, enterApp, exitToAuth],
  );

  return <AppModeContext.Provider value={value}>{children}</AppModeContext.Provider>;
}

export function useAppMode(): AppModeContextValue {
  const ctx = useContext(AppModeContext);
  if (!ctx) {
    throw new Error('useAppMode must be used within AppModeProvider');
  }
  return ctx;
}
