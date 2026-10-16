import { useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import {
  ChevronsLeft,
  ChevronsRight,
  Database,
  MessageSquare,
  Moon,
  Plug,
  Puzzle,
  Settings,
  Sun,
  Terminal,
  Wrench,
} from 'lucide-react';
import { AndroLogo } from '@/components/AndroLogo';
import { ProfileMenu } from '@/components/layout/ProfileMenu';
import { WorkspacePicker } from '@/components/WorkspacePicker';
import { checkAndromediaHealth, sourceTypeLabel } from '@/lib/andromediaApi';
import { useAppMode } from '@/hooks/useAppMode';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { useTheme } from '@/hooks/useTheme';
import { useTrialSession } from '@/hooks/useTrialSession';
import { cn } from '@/lib/utils';

export type AppPage = 'chat' | 'plugins' | 'tools' | 'connect' | 'dev' | 'crm';

interface SidebarProps {
  activePage: AppPage;
  onNavigate: (page: AppPage) => void;
  onOpenSettings: () => void;
}

const NAV_ITEMS: { id: AppPage; label: string; icon: typeof MessageSquare }[] = [
  { id: 'chat', label: 'Search', icon: MessageSquare },
  { id: 'crm', label: 'CRM', icon: Database },
  { id: 'plugins', label: 'Plugins', icon: Puzzle },
  { id: 'tools', label: 'Tools', icon: Wrench },
  { id: 'connect', label: 'Connect', icon: Plug },
  { id: 'dev', label: 'Developer', icon: Terminal },
];

const SIDEBAR_MIN_WIDTH = 180;
const SIDEBAR_MAX_WIDTH = 360;
const SIDEBAR_DEFAULT_WIDTH = 224;
const SIDEBAR_COLLAPSED_WIDTH = 56;

export function Sidebar({ activePage, onNavigate, onOpenSettings }: SidebarProps) {
  const { theme, toggleTheme } = useTheme();
  const overview = useIndexOverview();
  const { mode } = useAppMode();
  const { remaining, maxQueries } = useTrialSession();
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [width, setWidth] = useState(SIDEBAR_DEFAULT_WIDTH);
  const [isResizing, setIsResizing] = useState(false);
  const resizeRef = useRef<{ startX: number; startWidth: number } | null>(null);

  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      const up = await checkAndromediaHealth();
      if (!cancelled) {
        setApiUp(up);
      }
    };
    void refresh();
    const id = window.setInterval(() => {
      void refresh();
    }, 15_000);
    const onFocus = () => {
      void refresh();
    };
    window.addEventListener('focus', onFocus);
    return () => {
      cancelled = true;
      window.clearInterval(id);
      window.removeEventListener('focus', onFocus);
    };
  }, []);

  const startResize = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (collapsed || isResizing) {
      return;
    }
    resizeRef.current = { startX: event.clientX, startWidth: width };
    setIsResizing(true);
  };

  useEffect(() => {
    if (!isResizing) {
      return;
    }
    const onMouseMove = (event: MouseEvent) => {
      const start = resizeRef.current;
      if (!start) {
        return;
      }
      const delta = event.clientX - start.startX;
      const next = Math.min(
        SIDEBAR_MAX_WIDTH,
        Math.max(SIDEBAR_MIN_WIDTH, start.startWidth + delta),
      );
      setWidth(next);
    };
    const onMouseUp = () => {
      resizeRef.current = null;
      setIsResizing(false);
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [isResizing]);

  const apiLabel = apiUp === null ? 'checking…' : apiUp ? 'up' : 'down';
  const modeLabel =
    mode === 'trial'
      ? remaining != null
        ? `Trial · ${remaining}${maxQueries != null ? `/${maxQueries}` : ''} left`
        : 'Trial'
      : 'Local Pro';

  if (collapsed) {
    return (
      <aside
        style={{ width: SIDEBAR_COLLAPSED_WIDTH }}
        className="relative z-10 flex h-full shrink-0 flex-col items-center border-r border-border bg-card py-3"
      >
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          aria-label="Expand sidebar"
          title="Expand sidebar"
          className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
        >
          <ChevronsRight className="h-4 w-4" />
        </button>

        <nav className="mt-4 flex flex-col gap-1">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = activePage === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onNavigate(item.id)}
                aria-current={active ? 'page' : undefined}
                title={item.label}
                className={cn(
                  'flex h-8 w-8 cursor-pointer items-center justify-center rounded-md transition-colors',
                  active
                    ? 'bg-muted text-foreground'
                    : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground',
                )}
              >
                <Icon className="h-3.5 w-3.5" />
              </button>
            );
          })}
        </nav>

        <div className="mt-auto flex flex-col items-center gap-1">
          <span
            className={cn('mb-1 h-1.5 w-1.5 shrink-0 rounded-full', apiUp ? 'bg-primary' : 'bg-muted-foreground')}
            title={`Local API ${apiLabel}`}
          />
          <button
            type="button"
            onClick={toggleTheme}
            title="Appearance"
            className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
          >
            {theme === 'dark' ? <Sun className="h-3.5 w-3.5" /> : <Moon className="h-3.5 w-3.5" />}
          </button>
          <button
            type="button"
            onClick={onOpenSettings}
            title="Settings"
            className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
          >
            <Settings className="h-3.5 w-3.5" />
          </button>
        </div>
      </aside>
    );
  }

  return (
    <aside
      style={{ width }}
      className="relative z-10 flex h-full shrink-0 flex-col border-r border-border bg-card"
    >
      <div
        onMouseDown={startResize}
        className={cn(
          'absolute right-0 top-0 z-10 h-full w-1.5 translate-x-0.5 cursor-col-resize select-none hover:bg-muted-foreground/20',
          isResizing && 'bg-muted-foreground/30',
        )}
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize sidebar"
      />

      <div className="flex items-center justify-between px-4 py-4">
        <AndroLogo />
        <button
          type="button"
          onClick={() => setCollapsed(true)}
          aria-label="Minimize sidebar"
          title="Minimize sidebar"
          className="flex h-6 w-6 cursor-pointer items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
        >
          <ChevronsLeft className="h-3.5 w-3.5" />
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-0.5 px-2">
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          const active = activePage === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate(item.id)}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'flex h-8 cursor-pointer items-center gap-2 rounded-md px-2.5 text-[13px] transition-colors',
                active
                  ? 'bg-muted text-foreground'
                  : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground',
              )}
            >
              <Icon className="h-3.5 w-3.5 shrink-0" />
              {item.label}
            </button>
          );
        })}
      </nav>

      {/*
        Workspace switcher, then the sources inside it. The corpus is app-wide
        context rather than a property of one query, so it belongs in the
        sidebar — and the picker sits above the source list because it selects
        which corpus those sources describe.
      */}
      <div className="border-t border-border pt-1">
        <WorkspacePicker />
      </div>

      <div className="border-t border-border px-2 py-3">
        <p className="px-2.5 pb-1.5 text-[10px] uppercase tracking-wider text-muted-foreground">
          Indexed sources
        </p>
        {overview.loading && !overview.raw ? (
          <p className="px-2.5 text-[10px] text-muted-foreground">checking…</p>
        ) : overview.raw?.sourceTypes?.length ? (
          <ul className="space-y-0.5">
            {overview.raw.sourceTypes.map((type) => (
              <li
                key={type}
                className="flex items-center gap-2 rounded-md px-2.5 py-1 text-[11px] text-muted-foreground"
              >
                <span className="h-1 w-1 shrink-0 rounded-full bg-primary" />
                <span className="truncate">{sourceTypeLabel(type)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <button
            type="button"
            onClick={() => onNavigate('connect')}
            className="w-full cursor-pointer rounded-md px-2.5 py-1 text-left text-[10px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
          >
            Nothing indexed here yet
          </button>
        )}
        <p className="px-2.5 pt-1.5 text-[10px] tabular-nums text-muted-foreground">
          {overview.loading && !overview.raw
            ? '— chunks · —'
            : `${overview.totalChunks.toLocaleString()} chunks · ${overview.lastSync}`}
        </p>
      </div>

      <div className="space-y-1 border-t border-border px-2 py-3">
        <div className="flex items-center gap-2 px-2.5 py-1 text-[11px] text-muted-foreground">
          <span
            className={cn(
              'h-1.5 w-1.5 shrink-0 rounded-full',
              apiUp ? 'bg-primary' : 'bg-muted-foreground',
            )}
          />
          <span>API</span>
          <span className="ml-auto tabular-nums">{apiLabel}</span>
        </div>
        <p className="px-2.5 text-[10px] leading-4 text-muted-foreground">{modeLabel}</p>
        <ProfileMenu variant="sidebar" />
        <button
          type="button"
          onClick={toggleTheme}
          className="flex h-8 w-full cursor-pointer items-center gap-2 rounded-md px-2.5 text-[13px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
        >
          {theme === 'dark' ? (
            <Sun className="h-3.5 w-3.5" />
          ) : (
            <Moon className="h-3.5 w-3.5" />
          )}
          Appearance
        </button>
        <button
          type="button"
          onClick={onOpenSettings}
          className="flex h-8 w-full cursor-pointer items-center gap-2 rounded-md px-2.5 text-[13px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
        >
          <Settings className="h-3.5 w-3.5" />
          Settings
        </button>
      </div>
    </aside>
  );
}
