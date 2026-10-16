"use client";

import Link from "next/link";
import { Reveal } from "@/components/landing/reveal";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { faqItems, faqPage } from "@/lib/faq-content";

export function FaqPage() {
  return (
    <main className="flex-1 px-6 py-16 md:py-24">
      <div className="mx-auto max-w-3xl">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            {faqPage.eyebrow}
          </p>
          <h1 className="mt-4 text-balance text-4xl font-semibold tracking-tight md:text-5xl">
            {faqPage.heading}
          </h1>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            {faqPage.intro}
          </p>
        </Reveal>

        <Reveal delay={0.08}>
          <Accordion type="single" collapsible className="mt-12 border-t border-border">
            {faqItems.map((item) => (
              <AccordionItem key={item.question} value={item.question} className="border-border py-1">
                <AccordionTrigger className="py-4 text-base font-medium tracking-tight hover:no-underline">
                  {item.question}
                </AccordionTrigger>
                <AccordionContent className="pb-4 text-sm leading-relaxed text-muted-foreground">
                  <p>{item.answer}</p>
                </AccordionContent>
              </AccordionItem>
            ))}
          </Accordion>
        </Reveal>

        <Reveal delay={0.12}>
          <div className="mt-12 flex flex-wrap gap-3">
            <Button asChild variant="outline">
              <Link href={faqPage.docsCta.href}>{faqPage.docsCta.label}</Link>
            </Button>
            <Button asChild>
              <Link href={faqPage.trialCta.href}>{faqPage.trialCta.label}</Link>
            </Button>
          </div>
        </Reveal>
      </div>
    </main>
  );
}
