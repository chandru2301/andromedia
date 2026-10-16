"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutGrid, Search } from "lucide-react";

import { cn } from "@/lib/utils";

const navItems = [
  { href: "/app", label: "Sources", icon: LayoutGrid },
  { href: "/app/investigate", label: "Investigate", icon: Search },
] as const;

export function MobileBottomNav() {
  const pathname = usePathname();

  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-40 border-t border-border/60 bg-background/95 backdrop-blur-md md:hidden"
      aria-label="Product navigation"
    >
      <div className="mx-auto grid max-w-6xl grid-cols-2 gap-1 px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2">
        {navItems.map(({ href, label, icon: Icon }) => {
          const active = pathname === href;
          return (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex flex-col items-center gap-1 rounded-xl px-3 py-2 text-[11px] font-medium transition-colors",
                active
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
              )}
              aria-current={active ? "page" : undefined}
            >
              <Icon className="size-4" />
              {label}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
