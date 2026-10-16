import { Reveal } from "@/components/landing/reveal";
import { socialProof } from "@/lib/site-content";

/**
 * Renders nothing until a real quote is set in site-content.ts.
 * Never ship placeholder testimonials.
 */
export function SocialProof() {
  if (!socialProof) {
    return null;
  }

  return (
    <section className="border-t border-border px-6 py-24">
      <Reveal className="mx-auto max-w-2xl text-center">
        <blockquote className="text-balance text-xl font-medium tracking-tight md:text-2xl">
          “{socialProof.quote}”
        </blockquote>
        <p className="mt-6 font-mono text-xs text-muted-foreground">{socialProof.attribution}</p>
      </Reveal>
    </section>
  );
}
