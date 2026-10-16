"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { UserButton } from "@clerk/nextjs";
import { LayoutGrid, Search, Settings } from "lucide-react";
import { AndroLogo } from "@/components/app-shell/andro-logo";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";
import { checkAndromediaHealth } from "@/lib/api/andromedia";
import { cn } from "@/lib/utils";
import { useEffect, useState } from "react";

const navItems = [
  { href: "/app", label: "Sources", icon: LayoutGrid },
  { href: "/app/investigate", label: "Investigate", icon: Search },
] as const;

interface TopNavbarProps {
  onOpenSettings: () => void;
}

export function TopNavbar({ onOpenSettings }: TopNavbarProps) {
  const pathname = usePathname();
  const [apiUp, setApiUp] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;

    const refresh = async () => {
      const up = await checkAndromediaHealth();
      if (!cancelled) {
        setApiUp(up);
      }
    };

    void refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState !== "visible") {
        return;
      }
      void refresh();
    }, 60_000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-md">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-3 px-4 sm:h-16 sm:px-6">
        <Link href="/" className="shrink-0 text-foreground">
          <AndroLogo />
        </Link>

        <nav className="hidden items-center rounded-full border border-border bg-card/80 p-1 md:flex">
          {navItems.map(({ href, label, icon: Icon }) => {
            const active = pathname === href;
            return (
              <Link
                key={href}
                href={href}
                className={cn(
                  "flex items-center gap-1 rounded-full px-3 py-1.5 text-xs transition-colors",
                  active
                    ? "bg-background text-foreground"
                    : "text-muted-foreground hover:text-foreground",
                )}
                aria-current={active ? "page" : undefined}
              >
                <Icon className="size-3.5" />
                {label}
              </Link>
            );
          })}
        </nav>

        <div className="flex shrink-0 items-center gap-2 sm:gap-3">
          <div
            className="flex items-center gap-1.5 text-xs text-muted-foreground"
            title={apiUp === null ? "Checking API" : apiUp ? "API up" : "API down"}
          >
            <span className={`size-2 rounded-full ${apiUp ? "bg-primary" : "bg-muted-foreground"}`} />
            <span className="hidden sm:inline">
              {apiUp === null ? "api …" : apiUp ? "api up" : "api down"}
            </span>
          </div>
          <ThemeToggle />
          <UserButton />
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={onOpenSettings}
            aria-label="Open settings"
          >
            <Settings className="size-4" />
          </Button>
        </div>
      </div>
    </header>
  );
}
