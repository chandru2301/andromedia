import Link from "next/link";
import { Reveal } from "@/components/landing/reveal";
import { footerCta } from "@/lib/site-content";

export function FooterCta() {
  return (
    <section className="border-t border-border px-6 py-28">
      <Reveal className="mx-auto max-w-2xl text-center">
        <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
          {footerCta.eyebrow}
        </p>
        <p className="mt-4 rounded-lg border border-border bg-card px-6 py-4 font-mono text-lg text-foreground md:text-xl">
          {footerCta.command}
        </p>
        <p className="mt-5 text-lg text-muted-foreground">{footerCta.heading}</p>

        <div className="mt-8 flex flex-wrap items-center justify-center gap-x-6 gap-y-3">
          {footerCta.links.map((link) =>
            link.enabled === false ? (
              <span
                key={link.label}
                className="cursor-not-allowed text-sm text-muted-foreground/50"
              >
                {link.label}
              </span>
            ) : (
              <Link
                key={link.label}
                href={link.href ?? "#"}
                className="text-sm text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
              >
                {link.label}
              </Link>
            ),
          )}
        </div>
      </Reveal>
    </section>
  );
}
