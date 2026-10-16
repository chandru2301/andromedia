"use client";

import { useState, type ReactNode } from "react";

import { MobileBottomNav } from "@/components/app-shell/mobile-bottom-nav";
import { SettingsPanel } from "@/components/app-shell/settings-panel";
import { StatusBar } from "@/components/app-shell/status-bar";
import { TopNavbar } from "@/components/app-shell/top-navbar";
import { useTrial } from "@/hooks/use-trial";

export function ProductLayoutChrome({ children }: { children: ReactNode }) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const { loading, trialMode, isTrial, error } = useTrial();

  return (
    <>
      <TopNavbar onOpenSettings={() => setSettingsOpen(true)} />
      <main className="flex-1 px-4 py-6 pb-28 sm:px-6 sm:py-8 md:py-14 md:pb-14">
        <div className="mx-auto w-full max-w-6xl space-y-4">
          {trialMode && !loading && !isTrial && error ? (
            <div
              role="alert"
              className="rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-foreground"
            >
              {error}
            </div>
          ) : null}
          {children}
        </div>
      </main>
      <StatusBar />
      <MobileBottomNav />
      <SettingsPanel open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}
