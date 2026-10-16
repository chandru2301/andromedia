import { Reveal } from "@/components/landing/reveal";
import { howItWorks } from "@/lib/site-content";

export function HowItWorks() {
  return (
    <section id="how-it-works" className="border-t border-border px-6 py-24">
      <div className="mx-auto max-w-5xl">
        <Reveal className="text-center">
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {howItWorks.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {howItWorks.heading}
          </h2>
        </Reveal>

        <Reveal
          delay={0.1}
          className="mt-14 grid gap-px overflow-hidden rounded-xl border border-border bg-border md:grid-cols-3"
        >
          {howItWorks.steps.map((step) => (
            <div key={step.step} className="flex h-full flex-col bg-card p-7">
              <span className="font-mono text-xs text-mint">{step.step}</span>
              <h3 className="mt-4 text-lg font-medium tracking-tight">{step.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                {step.description}
              </p>

              {step.sources && (
                <ul className="mt-5 flex flex-wrap gap-2">
                  {step.sources.map((source) => (
                    <li
                      key={source}
                      className="rounded-md border border-border bg-secondary px-2.5 py-1 font-mono text-[11px] text-foreground"
                    >
                      {source}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </Reveal>
      </div>
    </section>
  );
}
