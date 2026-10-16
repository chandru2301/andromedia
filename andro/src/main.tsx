import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ClerkProvider } from '@clerk/clerk-react';
import './index.css';
import App from './App.tsx';
import { Toaster, useToast } from '@/components/ui/use-toast';
import { AndroConfigProvider } from '@/hooks/useAndroConfig';
import { AppModeProvider } from '@/hooks/useAppMode';
import { DesktopAuthProvider } from '@/hooks/useDesktopAuth';
import { ThemeProvider } from '@/hooks/useTheme';
import { TrialSessionProvider } from '@/hooks/useTrialSession';
import { WorkspaceProvider } from '@/hooks/useWorkspace';

const clerkPublishableKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY?.trim() ?? '';

function AppWithProviders() {
  const { toast } = useToast();

  return (
    <ThemeProvider>
      <AndroConfigProvider onSave={() => toast({ title: 'changes saved' })}>
        <App />
      </AndroConfigProvider>
    </ThemeProvider>
  );
}

function RootProviders({ children }: { children: React.ReactNode }) {
  // DesktopAuthProvider sits inside AppModeProvider (it calls enterApp) and,
  // when Clerk is configured, inside ClerkProvider (it redeems the ticket).
  const tree = (
    <AppModeProvider>
      <DesktopAuthProvider enabled={Boolean(clerkPublishableKey)}>
        <TrialSessionProvider>
          <WorkspaceProvider>{children}</WorkspaceProvider>
        </TrialSessionProvider>
      </DesktopAuthProvider>
    </AppModeProvider>
  );

  if (!clerkPublishableKey) {
    return tree;
  }

  return (
    <ClerkProvider
      publishableKey={clerkPublishableKey}
      afterSignOutUrl="/"
      signInFallbackRedirectUrl="/"
      signUpFallbackRedirectUrl="/"
    >
      {tree}
    </ClerkProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RootProviders>
      <Toaster>
        <AppWithProviders />
      </Toaster>
    </RootProviders>
  </StrictMode>,
);
