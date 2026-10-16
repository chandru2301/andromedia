"use client";

import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

const ROLES = ["Founder", "Eng", "Sales", "Other"] as const;

interface WaitlistFormProps {
  className?: string;
  submitLabel?: string;
}

export function WaitlistForm({
  className,
  submitLabel = "Join the waitlist",
}: WaitlistFormProps) {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [company, setCompany] = useState("");
  const [role, setRole] = useState("");
  const [notes, setNotes] = useState("");
  const [website, setWebsite] = useState("");
  const [status, setStatus] = useState<"idle" | "submitting" | "done" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setStatus("submitting");
    setError(null);
    try {
      const response = await fetch("/api/waitlist", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, email, company, role, notes, website }),
      });
      const data = (await response.json().catch(() => ({}))) as { error?: string };
      if (!response.ok) {
        throw new Error(data.error || "Could not join waitlist");
      }
      setStatus("done");
    } catch (err) {
      setStatus("error");
      setError(err instanceof Error ? err.message : "Could not join waitlist");
    }
  };

  if (status === "done") {
    return (
      <p className={cn("text-sm leading-relaxed text-muted-foreground", className)}>
        You’re on the list — we’ll email you when billing is live.
      </p>
    );
  }

  return (
    <form onSubmit={onSubmit} className={cn("relative space-y-4 text-left", className)}>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="waitlist-name">Name</Label>
          <Input
            id="waitlist-name"
            required
            autoComplete="name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Your name"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="waitlist-email">Email</Label>
          <Input
            id="waitlist-email"
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@company.com"
          />
        </div>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="waitlist-company">Company / team (optional)</Label>
          <Input
            id="waitlist-company"
            autoComplete="organization"
            value={company}
            onChange={(event) => setCompany(event.target.value)}
            placeholder="Acme"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="waitlist-role">Role (optional)</Label>
          <select
            id="waitlist-role"
            value={role}
            onChange={(event) => setRole(event.target.value)}
            className="flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground"
          >
            <option value="">Select</option>
            {ROLES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="space-y-2">
        <Label htmlFor="waitlist-notes">What do you need Andro for? (optional)</Label>
        <textarea
          id="waitlist-notes"
          rows={3}
          maxLength={2000}
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          className="flex w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          placeholder="CRM follow-ups, deal risk, onboarding…"
        />
      </div>
      {/* Honeypot */}
      <div className="absolute -left-[9999px] h-0 w-0 overflow-hidden" aria-hidden="true">
        <Label htmlFor="waitlist-website">Website</Label>
        <Input
          id="waitlist-website"
          tabIndex={-1}
          autoComplete="off"
          value={website}
          onChange={(event) => setWebsite(event.target.value)}
        />
      </div>
      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      <Button type="submit" className="w-full sm:w-auto" disabled={status === "submitting"}>
        {status === "submitting" ? "Joining…" : submitLabel}
      </Button>
    </form>
  );
}
