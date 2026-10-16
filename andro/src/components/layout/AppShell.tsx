import { Sidebar, type AppPage } from './Sidebar';

export type { AppPage };

interface AppShellProps {
  children: React.ReactNode;
  onOpenSettings: () => void;
  activePage: AppPage;
  onNavigate: (page: AppPage) => void;
}

export function AppShell({
  children,
  onOpenSettings,
  activePage,
  onNavigate,
}: AppShellProps) {
  // Full-height, non-scrolling pages that manage their own internal scrolling.
  const isFullBleedPage = activePage === 'chat' || activePage === 'crm';

  return (
    <div className="relative flex h-screen overflow-hidden bg-background">
      <Sidebar
        activePage={activePage}
        onNavigate={onNavigate}
        onOpenSettings={onOpenSettings}
      />
      <main
        className={
          isFullBleedPage
            ? 'relative z-10 min-h-0 min-w-0 flex-1 overflow-hidden'
            : 'relative z-10 min-w-0 flex-1 overflow-y-auto px-6 py-6'
        }
      >
        {children}
      </main>
    </div>
  );
}
