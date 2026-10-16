"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { SignIn, SignUp, useAuth } from "@clerk/nextjs";
import { useSearchParams } from "next/navigation";
import { LoaderCircle } from "lucide-react";
import { AuthShell } from "@/components/landing/auth-shell";
import { androClerkAppearance } from "@/lib/clerk-appearance";

type AuthMode = "sign-in" | "sign-up";

function resolveMode(raw: string | null): AuthMode {
  return raw === "sign-up" ? "sign-up" : "sign-in";
}

function DesktopReturn({ mode }: { mode: AuthMode }) {
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<"minting" | "redirecting" | "error">("minting");

  const handoff = useCallback(async () => {
    setStatus("minting");
    setError(null);
    try {
      const response = await fetch("/api/desktop/auth/ticket", { method: "POST" });
      const body = (await response.json().catch(() => ({}))) as {
        ticket?: string;
        error?: string;
      };
      if (!response.ok || !body.ticket) {
        throw new Error(body.error ?? `Ticket request failed (${response.status})`);
      }
      setStatus("redirecting");
      window.location.href = `andro://auth/callback?ticket=${encodeURIComponent(body.ticket)}`;
    } catch (caught) {
      setStatus("error");
      setError(caught instanceof Error ? caught.message : "Could not return to Andro");
    }
  }, []);

  useEffect(() => {
    void handoff();
  }, [handoff]);

  return (
    <div className="space-y-4 text-sm">
      <div className="flex items-start gap-3">
        {status !== "error" ? (
          <LoaderCircle className="mt-0.5 size-4 shrink-0 animate-spin text-mint" />
        ) : null}
        <div>
          <p className="font-medium text-foreground">
            {status === "error"
              ? "Could not open the desktop app"
              : status === "redirecting"
                ? "Returning to Andro…"
                : "Preparing your desktop session…"}
          </p>
          <p className="mt-1 leading-6 text-muted-foreground">
            {status === "error"
              ? error
              : "If Andro does not open automatically, check that the desktop app is installed and try again."}
          </p>
          <p className="mt-3 font-mono text-[11px] text-muted-foreground">
            You can close this tab after Andro unlocks Trial.
          </p>
        </div>
      </div>
      {status === "error" ? (
        <button
          type="button"
          className="rounded-md border border-border bg-secondary/60 px-3 py-2 text-xs font-medium text-foreground hover:bg-secondary"
          onClick={() => void handoff()}
        >
          Retry return to Andro
        </button>
      ) : null}
      <p className="font-mono text-[10px] text-muted-foreground">
        Mode: {mode === "sign-up" ? "create account" : "sign in"}
      </p>
    </div>
  );
}

function DesktopAuthInner() {
  const searchParams = useSearchParams();
  const mode = useMemo(() => resolveMode(searchParams.get("mode")), [searchParams]);
  const { isLoaded, isSignedIn } = useAuth();
  const redirectUrl = `/desktop/auth?mode=${mode}`;

  if (!isLoaded) {
    return (
      <AuthShell title="Opening Andro…" subtitle="Checking your session.">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" />
          Loading…
        </div>
      </AuthShell>
    );
  }

  if (isSignedIn) {
    return (
      <AuthShell
        title="You're signed in"
        subtitle="Sending your session back to the Andro desktop app."
      >
        <DesktopReturn mode={mode} />
      </AuthShell>
    );
  }

  if (mode === "sign-up") {
    return (
      <AuthShell
        title="Create your account"
        subtitle="Sign up in the browser, then Andro will open automatically with your Trial session."
      >
        <SignUp
          routing="hash"
          forceRedirectUrl={redirectUrl}
          signInUrl="/desktop/auth?mode=sign-in"
          appearance={androClerkAppearance}
        />
      </AuthShell>
    );
  }

  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in in the browser, then Andro will open automatically with your Trial session."
    >
      <SignIn
        routing="hash"
        forceRedirectUrl={redirectUrl}
        signUpUrl="/desktop/auth?mode=sign-up"
        appearance={androClerkAppearance}
      />
    </AuthShell>
  );
}

export default function DesktopAuthPage() {
  return (
    <Suspense
      fallback={
        <AuthShell title="Opening Andro…" subtitle="Checking your session.">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <LoaderCircle className="size-4 animate-spin" />
            Loading…
          </div>
        </AuthShell>
      }
    >
      <DesktopAuthInner />
    </Suspense>
  );
}
