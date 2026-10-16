import Link from "next/link";
import { PlayCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Reveal } from "@/components/landing/reveal";
import { HeroBackground } from "@/components/landing/hero-background";
import { HeroDemo } from "@/components/landing/hero-demo";
import { hero } from "@/lib/site-content";

export function Hero() {
  return (
    <section className="relative overflow-hidden px-6 pt-16 pb-16 md:pt-24 md:pb-24">
      <HeroBackground />

      <div className="relative z-10 mx-auto grid max-w-6xl items-center gap-12 md:grid-cols-2 md:gap-12">
        {/* Left: copy */}
        <div className="text-center md:text-left">
          <Reveal>
            <span className="inline-flex items-center gap-2 rounded-full border border-border/80 px-3 py-1 font-mono text-[11px] tracking-wide text-muted-foreground">
              <span className="size-1.5 rounded-full bg-mint shadow-[0_0_8px_var(--mint)]" />
              {hero.badge}
            </span>
          </Reveal>

          <Reveal delay={0.05}>
            <h1 className="mt-6 text-balance font-sans text-[clamp(2.25rem,5vw,4.25rem)] font-semibold leading-[1.05] tracking-tight">
              {hero.headline.map((line) => (
                <span key={line} className="block">
                  {line}
                </span>
              ))}
            </h1>
          </Reveal>

          <Reveal delay={0.1}>
            <p className="mx-auto mt-5 max-w-xl text-balance text-base text-muted-foreground md:mx-0 md:text-lg">
              {hero.subtext}
            </p>
          </Reveal>

          <Reveal delay={0.2}>
            <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row md:justify-start">
              <Button asChild size="lg" className="w-full sm:w-auto">
                <Link href={hero.primaryCta.href}>{hero.primaryCta.label}</Link>
              </Button>
              {hero.secondaryCta.enabled && (
                <Button asChild size="lg" variant="outline" className="w-full sm:w-auto">
                  <Link href={hero.secondaryCta.href ?? "#"}>
                    {hero.secondaryCta.label}
                    <PlayCircle className="size-4" />
                  </Link>
                </Button>
              )}
            </div>
          </Reveal>

          {hero.trustChips.length > 0 && (
            <Reveal delay={0.22}>
              <div className="mt-6 flex flex-wrap items-center justify-center gap-2 md:justify-start">
                {hero.trustChips.map((chip) => (
                  <span
                    key={chip}
                    className="rounded-full border border-border bg-secondary/60 px-3 py-1 font-mono text-[11px] text-muted-foreground"
                  >
                    {chip}
                  </span>
                ))}
              </div>
            </Reveal>
          )}

          <Reveal delay={0.25}>
            <p className="mx-auto mt-6 max-w-xl font-mono text-xs text-muted-foreground md:mx-0">
              {hero.proofStrip.text}{" "}
              <Link
                href={hero.proofStrip.href}
                className="text-foreground underline underline-offset-4 transition-colors hover:text-mint"
              >
                {hero.proofStrip.linkLabel} ↓
              </Link>
            </p>
          </Reveal>
        </div>

        {/* Right: live-feeling demo */}
        <Reveal delay={0.15} className="relative">
          <HeroDemo />
          <p className="mt-3 text-center text-xs text-muted-foreground">
            Sample preview — cycling through real question types.{" "}
            <Link href={hero.primaryCta.href} className="text-foreground underline underline-offset-4 hover:text-mint">
              Ask your own
            </Link>
          </p>
        </Reveal>
      </div>
    </section>
  );
}
