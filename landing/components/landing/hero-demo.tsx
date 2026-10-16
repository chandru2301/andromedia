"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Search } from "lucide-react";

type DemoRound = {
  question: string;
  answer: string;
  sources: string[];
};

const ROUNDS: DemoRound[] = [
  {
    question: "Which account should we focus on this quarter?",
    answer: "TechNova — CRM License Renewal, $250k, 92% probability, closing this month.",
    sources: ["zoho: TechNova — CRM License Renewal", "slack: #accounts, Aug 2"],
  },
  {
    question: "How do we onboard a new client?",
    answer:
      "Kickoff call → Jira project from the ONB template → Slack #client-<name> channel. Full steps in the Notion runbook.",
    sources: ["notion: Client Onboarding SOP", "jira: ONB project template"],
  },
  {
    question: "What did we decide about pricing in July?",
    answer:
      "₹10,500/mo for new deals, agreed in #sales-decisions on Aug 14 — supersedes the ₹12,000 still on the pricing page.",
    sources: ["slack: #sales-decisions, Aug 14", "notion: pricing-v2 — stale"],
  },
];

const TYPE_MS = 32;
const THINK_MS = 550;
const HOLD_MS = 3200;

type Phase = "typing" | "thinking" | "answering" | "holding";

export function HeroDemo() {
  const shouldReduceMotion = useReducedMotion();
  const [roundIndex, setRoundIndex] = useState(0);
  const [phase, setPhase] = useState<Phase>("typing");
  const [typed, setTyped] = useState("");

  const round = ROUNDS[roundIndex];

  // One self-contained timer chain per round — deliberately not split across
  // multiple effects: two effects sharing a single timer ref raced each
  // other (a phase-change effect's cleanup was cancelling this effect's
  // still-pending timeout), which froze the animation after the first frame.
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const current = ROUNDS[roundIndex];

    const advanceRound = () => {
      if (!cancelled) setRoundIndex((i) => (i + 1) % ROUNDS.length);
    };

    if (shouldReduceMotion) {
      setTyped(current.question);
      setPhase("holding");
      timer = setTimeout(advanceRound, HOLD_MS);
      return () => {
        cancelled = true;
        clearTimeout(timer);
      };
    }

    setTyped("");
    setPhase("typing");
    let charIndex = 0;

    const typeStep = () => {
      if (cancelled) return;
      charIndex += 1;
      setTyped(current.question.slice(0, charIndex));
      if (charIndex < current.question.length) {
        timer = setTimeout(typeStep, TYPE_MS);
        return;
      }
      timer = setTimeout(() => {
        if (cancelled) return;
        setPhase("thinking");
        timer = setTimeout(() => {
          if (cancelled) return;
          setPhase("answering");
          timer = setTimeout(() => {
            if (cancelled) return;
            setPhase("holding");
            timer = setTimeout(advanceRound, HOLD_MS);
          }, 500);
        }, THINK_MS);
      }, 400);
    };

    timer = setTimeout(typeStep, 300);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [roundIndex, shouldReduceMotion]);

  const showAnswer = phase === "answering" || phase === "holding";

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-card shadow-[0_0_0_1px_rgba(255,255,255,0.02),0_40px_80px_-40px_rgba(0,0,0,0.9)]">
      <div className="flex items-center gap-2 border-b border-border px-4 py-3">
        <span className="flex size-6 items-center justify-center rounded-md border border-mint/30 bg-mint/10">
          <Search className="size-3 text-mint" />
        </span>
        <span className="font-mono text-xs text-muted-foreground">andro.investigate</span>
        <span className="ml-auto rounded-full border border-border bg-secondary/60 px-2 py-0.5 font-mono text-[10px] text-muted-foreground">
          preview
        </span>
      </div>

      <div className="min-h-[220px] px-5 py-6">
        <div className="flex items-center gap-2 rounded-lg border border-input bg-background px-3 py-2.5">
          <span className="font-mono text-sm text-foreground">{typed}</span>
          {phase === "typing" && (
            <span className="h-4 w-px animate-pulse bg-mint" aria-hidden="true" />
          )}
        </div>

        <div className="mt-4 min-h-[120px]">
          {phase === "thinking" && (
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <span className="flex gap-1">
                <span className="size-1 animate-bounce rounded-full bg-mint [animation-delay:-0.2s]" />
                <span className="size-1 animate-bounce rounded-full bg-mint [animation-delay:-0.1s]" />
                <span className="size-1 animate-bounce rounded-full bg-mint" />
              </span>
              grounding against your indexed sources…
            </div>
          )}

          <AnimatePresence mode="wait">
            {showAnswer && (
              <motion.div
                key={roundIndex}
                initial={shouldReduceMotion ? undefined : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.35, ease: "easeOut" }}
              >
                <p className="text-sm leading-relaxed text-foreground">{round.answer}</p>
                <ul className="mt-3 flex flex-wrap gap-2">
                  {round.sources.map((source) => (
                    <li
                      key={source}
                      className="rounded-md border border-border bg-secondary px-2 py-1 font-mono text-[10px] text-muted-foreground"
                    >
                      {source}
                    </li>
                  ))}
                </ul>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}
