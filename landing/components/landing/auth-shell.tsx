import Link from "next/link";
import type { ReactNode } from "react";
import { AndroLogo } from "@/components/app-shell/andro-logo";
import { ThemeToggle } from "@/components/theme-toggle";

const VALUE_POINTS = [
  {
    title: "One memory across tools",
    body: "Slack, Notion, Jira, Linear, and Zoho CRM indexed together.",
  },
  {
    title: "Grounded answers",
    body: "Investigate against real records — not a single chat transcript.",
  },
  {
    title: "Local or hosted trial",
    body: "Start in the browser trial, or run the desktop app fully local.",
  },
] as const;

interface AuthShellProps {
  title: string;
  subtitle: string;
  children: ReactNode;
}

export function AuthShell({ title, subtitle, children }: AuthShellProps) {
  return (
    <div className="relative min-h-screen bg-background text-foreground">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 overflow-hidden"
      >
        <div className="absolute -left-1/4 top-0 h-[42rem] w-[42rem] rounded-full bg-mint/[0.07] blur-3xl" />
        <div className="absolute -right-1/4 bottom-0 h-[36rem] w-[36rem] rounded-full bg-mint/[0.04] blur-3xl" />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_transparent_20%,_var(--background)_70%)]" />
      </div>

      <header className="relative z-10 mx-auto flex w-full max-w-6xl items-center justify-between px-6 py-5">
        <Link
          href="/"
          className="rounded-md outline-none transition-opacity hover:opacity-80 focus-visible:ring-2 focus-visible:ring-mint"
        >
          <AndroLogo />
        </Link>
        <div className="flex items-center gap-3">
          <ThemeToggle />
          <Link
            href="/"
            className="hidden text-sm text-muted-foreground transition-colors hover:text-foreground sm:inline"
          >
            Back to site
          </Link>
        </div>
      </header>

      <main className="relative z-10 mx-auto grid w-full max-w-6xl flex-1 gap-10 px-6 pb-16 pt-4 lg:grid-cols-[1.05fr_0.95fr] lg:items-center lg:gap-16 lg:pb-24 lg:pt-8">
        <section className="mx-auto w-full max-w-lg lg:mx-0">
          <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-muted-foreground">
            Andro account
          </p>
          <h1 className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl">
            {title}
          </h1>
          <p className="mt-3 max-w-md text-pretty text-sm leading-6 text-muted-foreground sm:text-base">
            {subtitle}
          </p>

          <ul className="mt-10 hidden space-y-5 lg:block">
            {VALUE_POINTS.map((point) => (
              <li key={point.title} className="flex gap-3">
                <span
                  aria-hidden
                  className="mt-1.5 size-1.5 shrink-0 rounded-full bg-mint shadow-[0_0_10px_var(--mint)]"
                />
                <div>
                  <p className="text-sm font-medium text-foreground">{point.title}</p>
                  <p className="mt-0.5 text-sm leading-5 text-muted-foreground">
                    {point.body}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="mx-auto w-full max-w-md lg:mx-0 lg:justify-self-end">
          <div className="rounded-2xl border border-border/80 bg-card/70 p-6 backdrop-blur-md sm:p-8">
            {children}
          </div>
          <p className="mt-4 text-center font-mono text-[11px] text-muted-foreground">
            By continuing you agree to use Andro for your team&apos;s work data.
          </p>
        </section>
      </main>
    </div>
  );
}
