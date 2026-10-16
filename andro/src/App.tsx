import { useEffect, useState } from 'react';
import { AppShell, type AppPage } from '@/components/layout/AppShell';
import { SettingsPanel } from '@/components/SettingsPanel';
import { useAppMode } from '@/hooks/useAppMode';
import { useTrialSession } from '@/hooks/useTrialSession';
import { AuthPage } from '@/pages/auth-page';
import { DashboardPage } from '@/pages/DashboardPage';
import { InvestigationPage } from '@/pages/investigation-page';
import { ToolsPage } from '@/pages/tools-page';
import { ConnectPage } from '@/pages/connect-page';
import { DevPage } from '@/pages/dev-page';
import { DeveloperChatPage } from '@/pages/developer-chat-page';

export default function App() {
  const { hasEnteredApp } = useAppMode();
  const { canUseApp, loading, error } = useTrialSession();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [activePage, setActivePage] = useState<AppPage>('chat');

  // Desktop app menu → in-app actions (Cmd/Ctrl+, opens Settings).
  useEffect(() => {
    const bridge = window.androDesktop?.dev;
    if (!bridge) {
      return;
    }
    const offSettings = bridge.onMenu('andro:open-settings', () => setSettingsOpen(true));
    const offMcp = bridge.onMenu('andro:copy-mcp-config', () => setActivePage('connect'));
    return () => {
      offSettings();
      offMcp();
    };
  }, []);

  if (!hasEnteredApp || !canUseApp) {
    if (hasEnteredApp && loading) {
      return (
        <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">
          Starting session…
        </div>
      );
    }
    return (
      <>
        {error && hasEnteredApp ? (
          <div className="border-b border-destructive/30 bg-destructive/10 px-4 py-2 text-center text-xs text-destructive">
            {error}
          </div>
        ) : null}
        <AuthPage />
      </>
    );
  }

  return (
    <>
      <AppShell
        onOpenSettings={() => setSettingsOpen(true)}
        activePage={activePage}
        onNavigate={setActivePage}
      >
        {activePage === 'chat' ? (
          <DeveloperChatPage />
        ) : activePage === 'crm' ? (
          <InvestigationPage />
        ) : activePage === 'plugins' ? (
          <DashboardPage />
        ) : activePage === 'tools' ? (
          <ToolsPage />
        ) : activePage === 'dev' ? (
          <DevPage />
        ) : (
          <ConnectPage />
        )}
      </AppShell>
      <SettingsPanel open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}
