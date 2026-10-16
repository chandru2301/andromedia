import { MessageSquare, FileText, ArrowRight } from "lucide-react";
import { Reveal } from "@/components/landing/reveal";
import { memoryPitch } from "@/lib/site-content";

export function MemoryPitch() {
  const example = memoryPitch.example;

  return (
    <section id="one-memory" className="border-t border-border px-6 py-20">
      <Reveal className="mx-auto max-w-3xl text-center">
        <p className="flex items-center justify-center gap-2 font-mono text-xs uppercase tracking-widest text-mint">
          {memoryPitch.eyebrow}
          {!memoryPitch.live && (
            <span className="rounded-full border border-border bg-secondary px-2 py-0.5 text-[10px] text-muted-foreground">
              SOON
            </span>
          )}
        </p>
        <h2 className="mx-auto mt-4 max-w-xl text-balance text-2xl font-semibold tracking-tight md:text-3xl">
          {memoryPitch.heading}
        </h2>
        <p className="mx-auto mt-4 max-w-xl text-balance text-sm leading-relaxed text-muted-foreground">
          {memoryPitch.body}
        </p>
      </Reveal>

      {example && (
        <Reveal delay={0.1} className="mx-auto mt-10 max-w-4xl">
          <p className="mb-4 text-center font-mono text-xs text-muted-foreground">
            <span className="text-mint">Q:</span> {example.question}
          </p>

          <div className="relative grid items-stretch gap-3 md:grid-cols-2">
            <div className="rounded-2xl border border-border bg-card p-5">
              <div className="flex items-center gap-2">
                <span className="flex size-7 items-center justify-center rounded-md bg-secondary">
                  <FileText className="size-3.5 text-muted-foreground" />
                </span>
                <span className="text-sm font-medium">Notion says</span>
                <span className="ml-auto rounded-full border border-border bg-secondary px-2 py-0.5 font-mono text-[10px] text-muted-foreground">
                  stale
                </span>
              </div>
              <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
                pricing-v2 — <span className="text-foreground">₹12,000/mo</span>, last edited 3 months ago.
              </p>
            </div>

            <div className="rounded-2xl border border-border bg-card p-5">
              <div className="flex items-center gap-2">
                <span className="flex size-7 items-center justify-center rounded-md bg-secondary">
                  <MessageSquare className="size-3.5 text-muted-foreground" />
                </span>
                <span className="text-sm font-medium">Slack says</span>
                <span className="ml-auto rounded-full border border-mint/40 bg-mint/10 px-2 py-0.5 font-mono text-[10px] text-mint">
                  Aug 14
                </span>
              </div>
              <p className="mt-3 text-sm leading-relaxed text-muted-foreground">
                #sales-decisions — <span className="text-foreground">₹10,500/mo</span> agreed for new deals.
              </p>
            </div>

            <div className="pointer-events-none absolute left-1/2 top-1/2 hidden size-9 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border border-border bg-background text-muted-foreground md:flex">
              <ArrowRight className="size-3.5 rotate-90" />
            </div>
          </div>

          <div className="mx-auto mt-3 max-w-2xl rounded-2xl border border-mint/40 bg-gradient-to-b from-mint/10 to-card p-5 text-left shadow-[0_30px_80px_-60px_var(--mint)]">
            <div className="flex items-center gap-2">
              <span className="rounded-full border border-mint/40 bg-mint/10 px-2.5 py-0.5 font-mono text-[10px] tracking-wide text-mint">
                ANDRO RESOLVES
              </span>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-foreground">{example.answer}</p>
            <ul className="mt-4 flex flex-wrap gap-2">
              {example.sources.map((source) => (
                <li
                  key={source}
                  className="rounded-md border border-border bg-secondary px-2 py-1 font-mono text-[10px] text-muted-foreground"
                >
                  {source}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>
      )}
    </section>
  );
}
