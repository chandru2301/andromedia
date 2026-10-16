"use client";

import { useEffect, useId, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Menu, X } from "lucide-react";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";
import { nav, siteConfig } from "@/lib/site-content";

function isHashOnly(href: string) {
  return href.startsWith("#");
}

function NavLink({
  href,
  label,
  onNavigate,
  className,
}: {
  href: string;
  label: string;
  onNavigate?: () => void;
  className: string;
}) {
  if (isHashOnly(href)) {
    return (
      <a href={href} className={className} onClick={onNavigate}>
        {label}
      </a>
    );
  }

  return (
    <Link href={href} className={className} onClick={onNavigate}>
      {label}
    </Link>
  );
}

export function SiteHeader() {
  const [menuOpen, setMenuOpen] = useState(false);
  const pathname = usePathname();
  const menuId = useId();

  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!menuOpen) {
      return;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [menuOpen]);

  const closeMenu = () => setMenuOpen(false);

  return (
    <header className="sticky top-0 z-50 w-full border-b border-border/60 bg-background/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-3 px-6">
        <Link href="/" className="flex items-center" aria-label={`${siteConfig.name} home`}>
          {/* Wordmark ships light for dark backgrounds; brightness-0 flips it to
              solid black in the light theme. */}
          <Image
            src="/brand/andro-wordmark.png"
            alt={siteConfig.name}
            width={107}
            height={20}
            className="h-5 w-auto [[data-theme='light']_&]:brightness-0"
            priority
          />
        </Link>

        <nav className="hidden items-center gap-8 md:flex" aria-label="Primary">
          {nav.links.map((link) => (
            <NavLink
              key={link.href}
              href={link.href}
              label={link.label}
              className="text-sm text-muted-foreground transition-colors hover:text-foreground"
            />
          ))}
        </nav>

        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Button asChild size="sm">
            <Link href={nav.primaryCta.href}>{nav.primaryCta.label}</Link>
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="md:hidden"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            aria-label={menuOpen ? "Close menu" : "Open menu"}
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <X className="size-4" /> : <Menu className="size-4" />}
          </Button>
        </div>
      </div>

      {menuOpen ? (
        <nav
          id={menuId}
          aria-label="Mobile"
          className="border-t border-border/60 md:hidden"
        >
          <div className="mx-auto flex max-w-6xl flex-col gap-1 px-6 py-3">
            {nav.links.map((link) => (
              <NavLink
                key={link.href}
                href={link.href}
                label={link.label}
                onNavigate={closeMenu}
                className="rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors hover:bg-muted"
              />
            ))}
          </div>
        </nav>
      ) : null}
    </header>
  );
}
