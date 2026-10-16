"use client";

import Link from "next/link";

import { FeedbackForm } from "@/components/feedback-form";
import { Button } from "@/components/ui/button";
import { siteConfig } from "@/lib/site-content";

interface TrialLimitPanelProps {
  queriesUsed: number;
  maxQueries: number;
}

export function TrialLimitPanel({ queriesUsed, maxQueries }: TrialLimitPanelProps) {
  return (
    <section className="rounded-xl border border-primary/30 bg-primary/5 p-5 sm:p-6">
      <h2 className="text-sm font-medium text-foreground">
        You&apos;ve used all {maxQueries} free questions in this trial.
      </h2>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">
        Loved the answers? Here&apos;s what&apos;s next:
      </p>
      <ul className="mt-3 space-y-2 text-sm leading-6 text-muted-foreground">
        <li>Self-host Andro with your own LLM key (free, unlimited)</li>
        <li>Or reach out for extended trial access while we gather early feedback</li>
      </ul>
      <p className="mt-3 text-xs text-muted-foreground">
        Questions used: {queriesUsed} / {maxQueries}
      </p>
      <div className="mt-5 flex flex-col gap-2 sm:flex-row">
        <Button asChild variant="default" size="sm">
          <a href={siteConfig.setupGuidePath} target="_blank" rel="noopener noreferrer">
            Self-host setup guide
          </a>
        </Button>
        {siteConfig.demoBookingUrl ? (
          <Button asChild variant="outline" size="sm">
            <Link href={siteConfig.demoBookingUrl}>Request extended trial</Link>
          </Button>
        ) : null}
      </div>
      <div className="mt-6 border-t border-border/60 pt-5">
        <FeedbackForm
          context="trial_limit"
          page="/app"
          heading="Quick feedback"
          description="What worked? What didn’t? This helps us prioritize the next build."
        />
      </div>
    </section>
  );
}
