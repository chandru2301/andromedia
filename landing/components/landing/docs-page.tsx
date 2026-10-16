"use client";

import type { ReactNode } from "react";
import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { FeedbackForm } from "@/components/feedback-form";
import { Reveal } from "@/components/landing/reveal";
import { Button } from "@/components/ui/button";
import {
  docsConnectingZoho,
  docsFeedback,
  docsLimitations,
  docsQuestions,
  docsQuickStart,
  docsReadingAnswers,
  type DocsScreenshotSlot,
} from "@/lib/docs-content";

function DocsScreenshot({ image }: { image: DocsScreenshotSlot }) {
  if (image.placeholder) {
    return (
      <div
        aria-hidden
        className="mt-5 flex aspect-[16/10] w-full items-center justify-center rounded-xl border border-dashed border-border bg-card/60 px-6 text-center"
      >
        <p className="font-mono text-xs leading-relaxed text-muted-foreground">
          {image.placeholderLabel}
          <span className="mt-1 block text-[10px] opacity-70">{image.src}</span>
        </p>
      </div>
    );
  }

  return (
    <div className="mt-5 overflow-hidden rounded-xl border border-border bg-card shadow-[0_0_0_1px_rgba(255,255,255,0.02)]">
      <Image
        src={image.src}
        alt={image.alt}
        width={image.width}
        height={image.height}
        sizes="(max-width: 768px) 100vw, 768px"
        className="h-auto w-full"
      />
    </div>
  );
}

function SectionShell({
  id,
  children,
  className = "",
}: {
  id?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section id={id} className={`border-t border-border px-6 py-20 md:py-24 ${className}`}>
      <div className="mx-auto max-w-3xl">{children}</div>
    </section>
  );
}

export function DocsPage() {
  return (
    <main className="flex-1">
      {/* 1. Quick start — above the fold */}
      <section className="px-6 pt-16 pb-20 md:pt-24 md:pb-28">
        <div className="mx-auto max-w-3xl">
          <Reveal>
            <h1 className="text-balance text-4xl font-semibold tracking-tight md:text-5xl">
              {docsQuickStart.heading}
            </h1>
          </Reveal>

          <Reveal delay={0.08}>
            <ol className="mt-10 space-y-4">
              {docsQuickStart.steps.map((step, index) => (
                <li key={step} className="flex gap-4 text-base leading-relaxed text-foreground md:text-lg">
                  <span className="font-mono text-sm text-mint tabular-nums">{index + 1}.</span>
                  <span>{step}</span>
                </li>
              ))}
            </ol>
          </Reveal>

          <Reveal delay={0.16}>
            <div className="mt-10">
              <Button asChild size="lg">
                <Link href={docsQuickStart.cta.href}>
                  {docsQuickStart.cta.label}
                  <ArrowRight className="size-4" />
                </Link>
              </Button>
            </div>
          </Reveal>
        </div>
      </section>

      {/* 2. Connecting Zoho */}
      <SectionShell id="connecting-zoho">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {docsConnectingZoho.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {docsConnectingZoho.heading}
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            {docsConnectingZoho.intro}
          </p>
        </Reveal>

        <div className="mt-12 space-y-14">
          {docsConnectingZoho.steps.map((step, index) => (
            <Reveal key={step.title} delay={index * 0.05}>
              <div>
                <p className="font-mono text-xs text-mint">
                  Step {index + 1}
                </p>
                <h3 className="mt-2 text-lg font-medium tracking-tight">{step.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
                {"code" in step && step.code ? (
                  <pre className="mt-3 overflow-x-auto rounded-lg border border-border bg-card px-4 py-3 font-mono text-xs text-foreground">
                    {step.code}
                  </pre>
                ) : null}
                {step.image ? <DocsScreenshot image={step.image} /> : null}
              </div>
            </Reveal>
          ))}
        </div>
      </SectionShell>

      {/* 3. What questions work */}
      <SectionShell id="questions">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {docsQuestions.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {docsQuestions.heading}
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            {docsQuestions.intro}
          </p>
        </Reveal>

        <Reveal delay={0.08}>
          <ul className="mt-8 space-y-3">
            {docsQuestions.good.map((q) => (
              <li
                key={q}
                className="flex gap-3 rounded-xl border border-border bg-card/80 px-4 py-3 text-sm leading-relaxed text-foreground"
              >
                <span className="shrink-0 text-mint" aria-hidden>
                  ✓
                </span>
                <span>{q}</span>
              </li>
            ))}
          </ul>
        </Reveal>

        <Reveal delay={0.12}>
          <p className="mt-8 text-sm leading-relaxed text-muted-foreground">{docsQuestions.note}</p>
        </Reveal>
      </SectionShell>

      {/* 4. Reading answers */}
      <SectionShell id="reading-answers">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {docsReadingAnswers.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {docsReadingAnswers.heading}
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            {docsReadingAnswers.intro}
          </p>
        </Reveal>

        <Reveal delay={0.08}>
          <dl className="mt-8 space-y-5">
            {docsReadingAnswers.parts.map((part) => (
              <div key={part.title}>
                <dt className="text-sm font-medium text-foreground">{part.title}</dt>
                <dd className="mt-1 text-sm leading-relaxed text-muted-foreground">
                  {part.description}
                </dd>
              </div>
            ))}
          </dl>
        </Reveal>

        <Reveal delay={0.12}>
          <DocsScreenshot image={docsReadingAnswers.image} />
        </Reveal>

        <Reveal delay={0.16}>
          <p className="mt-6 text-sm leading-relaxed text-muted-foreground">
            {docsReadingAnswers.note}
          </p>
        </Reveal>
      </SectionShell>

      {/* 5. Limitations */}
      <SectionShell id="limitations">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {docsLimitations.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {docsLimitations.heading}
          </h2>
        </Reveal>

        <Reveal delay={0.08}>
          <ul className="mt-8 list-disc space-y-3 pl-5 text-sm leading-relaxed text-muted-foreground">
            {docsLimitations.items.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <p className="mt-6 text-sm leading-relaxed text-foreground">{docsLimitations.closing}</p>
        </Reveal>
      </SectionShell>

      {/* 6. Feedback */}
      <SectionShell id="feedback" className="pb-28">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {docsFeedback.eyebrow}
          </p>
          <h2 className="mt-4 text-balance text-3xl font-semibold tracking-tight md:text-4xl">
            {docsFeedback.heading}
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            {docsFeedback.body}
          </p>
        </Reveal>

        <Reveal delay={0.08}>
          <div className="mt-8 flex flex-wrap gap-3">
            {docsFeedback.links.map((link) => (
              <Button key={link.label} asChild variant="outline">
                <a href={link.href} target={link.href.startsWith("http") ? "_blank" : undefined} rel={link.href.startsWith("http") ? "noreferrer" : undefined}>
                  {link.label}
                </a>
              </Button>
            ))}
          </div>
        </Reveal>

        <Reveal delay={0.12}>
          <p className="mt-8 text-sm font-medium text-foreground">Tell us:</p>
          <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
            {docsFeedback.prompts.map((prompt) => (
              <li key={prompt}>{prompt}</li>
            ))}
          </ul>
        </Reveal>

        <Reveal delay={0.16}>
          <FeedbackForm
            className="mt-10 max-w-xl"
            context="docs"
            page="/docs"
            heading="Send feedback"
            description="Sign in with your trial account, then tell us what broke."
          />
        </Reveal>
      </SectionShell>
    </main>
  );
}
