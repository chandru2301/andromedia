import Link from "next/link";
import { ShieldCheck } from "lucide-react";
import { Reveal } from "@/components/landing/reveal";
import { trust } from "@/lib/site-content";

export function TrustSection() {
  return (
    <section className="border-t border-border px-6 py-24">
      <div className="mx-auto max-w-3xl space-y-8">
        <Reveal>
          <div className="rounded-2xl border border-border bg-card p-8 text-center md:p-10">
            <p className="font-mono text-xs uppercase tracking-widest text-mint">Proof</p>
            <h2 className="mt-4 text-balance text-2xl font-semibold tracking-tight md:text-3xl">
              {trust.proofHeading}
            </h2>
            <p className="mx-auto mt-4 max-w-xl text-sm leading-relaxed text-muted-foreground">
              {trust.proofBody}
            </p>
            <Link
              href={trust.proofHref}
              className="mt-6 inline-block font-mono text-xs text-muted-foreground underline underline-offset-4 transition-colors hover:text-foreground"
            >
              {trust.proofLinkLabel} →
            </Link>
          </div>
        </Reveal>

        <Reveal delay={0.1}>
          <div className="rounded-2xl border border-border bg-card p-8 text-center md:p-12">
            <div className="mx-auto flex size-11 items-center justify-center rounded-full border border-border bg-secondary">
              <ShieldCheck className="size-5 text-mint" />
            </div>

            <h2 className="mt-6 text-balance text-2xl font-semibold tracking-tight md:text-3xl">
              {trust.heading}
            </h2>

            <ul className="mt-8 space-y-5 text-left">
              {trust.points.map((point) => (
                <li
                  key={point.title}
                  className="flex gap-3 border-t border-border/60 pt-5 first:border-t-0 first:pt-0"
                >
                  <span className="mt-1 text-mint">→</span>
                  <div>
                    <p className="text-sm font-medium text-foreground">{point.title}</p>
                    <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                      {point.description}
                    </p>
                  </div>
                </li>
              ))}
            </ul>

            <Link
              href={trust.setupHref}
              className="mt-8 inline-block font-mono text-xs text-muted-foreground underline underline-offset-4 transition-colors hover:text-foreground"
            >
              {trust.setupLabel} →
            </Link>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
