"use client";

import { Info } from "lucide-react";
import { Reveal } from "@/components/landing/reveal";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { BRAND_META } from "@/data/integration-brands";
import { connectors, connectorsFootnote } from "@/lib/site-content";
import { cn } from "@/lib/utils";

export function ConnectorGrid() {
  return (
    <section id="sources" className="border-t border-border px-6 py-24">
      <div className="mx-auto max-w-5xl">
        <Reveal className="text-center">
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            Sources
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            Connect what your team already uses.
          </h2>
        </Reveal>

        <Reveal delay={0.1} className="mt-12 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
          {connectors.map((connector) => {
            const brand = BRAND_META[connector.brandId];
            const isAvailable = connector.status === "available";

            return (
              <div
                key={connector.brandId}
                className={
                  isAvailable
                    ? "flex items-center justify-between gap-2 rounded-lg border border-border bg-card px-4 py-3"
                    : "flex items-center justify-between gap-2 rounded-lg border border-border/50 bg-card/30 px-4 py-3"
                }
              >
                <span className="flex min-w-0 items-center gap-2">
                  <img
                    src={brand.logo}
                    alt=""
                    width={24}
                    height={24}
                    aria-hidden
                    draggable={false}
                    className={cn(
                      "size-6 shrink-0 object-contain",
                      !isAvailable && "opacity-50",
                    )}
                  />
                  <span
                    className={
                      isAvailable
                        ? "truncate text-sm text-foreground"
                        : "truncate text-sm text-muted-foreground"
                    }
                  >
                    {connector.name}
                  </span>
                  {connector.note ? (
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button
                          type="button"
                          className="inline-flex shrink-0 text-muted-foreground transition-colors hover:text-foreground"
                          aria-label={`${connector.name} details`}
                        >
                          <Info className="size-3.5" />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent side="top" sideOffset={6}>
                        {connector.note}
                      </TooltipContent>
                    </Tooltip>
                  ) : null}
                </span>
                <span
                  className={
                    isAvailable
                      ? "shrink-0 font-mono text-[10px] text-mint"
                      : "shrink-0 font-mono text-[10px] text-muted-foreground"
                  }
                >
                  {isAvailable ? "LIVE" : "SOON"}
                </span>
              </div>
            );
          })}
        </Reveal>

        <Reveal delay={0.15}>
          <p className="mx-auto mt-6 max-w-2xl text-center text-xs leading-relaxed text-muted-foreground">
            {connectorsFootnote}
          </p>
        </Reveal>
      </div>
    </section>
  );
}
