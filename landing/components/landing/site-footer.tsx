import Link from "next/link";
import { siteConfig } from "@/lib/site-content";

export function SiteFooter() {
  return (
    <footer className="border-t border-border px-6 py-8">
      <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-3 sm:flex-row">
        <p className="font-mono text-xs text-muted-foreground">
          © {new Date().getFullYear()} {siteConfig.name}
        </p>
        <nav className="flex items-center gap-4" aria-label="Footer">
          <Link
            href={siteConfig.docsUrl}
            className="text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            Docs
          </Link>
          <Link
            href={siteConfig.faqUrl}
            className="text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            FAQ
          </Link>
        </nav>
        <p className="text-xs text-muted-foreground">Local-first context for AI coding agents.</p>
      </div>
    </footer>
  );
}
