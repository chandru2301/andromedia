"use client";

import { WaitlistForm } from "@/components/landing/waitlist-form";
import { Reveal } from "@/components/landing/reveal";
import { BorderGlow } from "@/components/react-bits/border-glow";
import { Button } from "@/components/ui/button";
import { Check } from "lucide-react";
import Link from "next/link";
import { pricing } from "@/lib/site-content";

const pricingGlowProps = {
  backgroundColor: "#0a0a0a",
  borderRadius: 12,
  glowColor: "152 60 55",
  glowRadius: 28,
  glowIntensity: 0.85,
  coneSpread: 22,
  animated: false,
  colors: ["#3dd68c", "#88a899", "#1a3d2e"],
} as const;

export function PricingSection() {
  return (
    <section id="pricing" className="border-t border-border px-6 py-24">
      <div className="mx-auto max-w-4xl">
        <Reveal className="text-center">
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {pricing.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {pricing.heading}
          </h2>
        </Reveal>

        {!pricing.billingLive ? (
          <Reveal delay={0.1} className="mx-auto mt-12 max-w-lg overflow-visible py-2">
            <BorderGlow {...pricingGlowProps} className="w-full">
              <div className="relative p-8">
                <p className="text-center text-sm leading-relaxed text-muted-foreground">
                  {pricing.waitlistBody}
                </p>
                <WaitlistForm className="mt-8" submitLabel={pricing.waitlistCta.label} />
              </div>
            </BorderGlow>
          </Reveal>
        ) : (
          <div className="mt-12 grid gap-6 overflow-visible md:grid-cols-2">
            {pricing.plans.map((plan, index) => (
              <Reveal key={plan.name} delay={index * 0.1} className="overflow-visible py-2">
                <BorderGlow {...pricingGlowProps} className="h-full w-full">
                  <div className="flex h-full flex-col p-6">
                    <div className="flex items-baseline justify-between">
                      <h3 className="text-lg font-medium">{plan.name}</h3>
                      <span className="font-mono text-xl">{plan.price}</span>
                    </div>
                    <p className="mt-2 text-sm text-muted-foreground">{plan.description}</p>

                    <ul className="mt-6 flex-1 space-y-3">
                      {plan.features.map((feature) => (
                        <li key={feature} className="flex items-center gap-2 text-sm">
                          <Check className="size-3.5 shrink-0 text-mint" />
                          <span className="text-foreground">{feature}</span>
                        </li>
                      ))}
                    </ul>

                    {plan.name === "Pro" ? (
                      <div className="mt-8">
                        <WaitlistForm submitLabel={plan.cta.label} />
                      </div>
                    ) : (
                      <Button asChild className="mt-8" variant="default">
                        <Link href={plan.cta.href}>{plan.cta.label}</Link>
                      </Button>
                    )}
                  </div>
                </BorderGlow>
              </Reveal>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
