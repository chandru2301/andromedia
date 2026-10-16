"use client";

import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

export type FeedbackContext = "trial_limit" | "docs" | "general";

interface FeedbackFormProps {
  context: FeedbackContext;
  page?: string;
  className?: string;
  heading?: string;
  description?: string;
}

export function FeedbackForm({
  context,
  page,
  className,
  heading = "Send feedback",
  description = "What worked? What didn’t?",
}: FeedbackFormProps) {
  const [message, setMessage] = useState("");
  const [rating, setRating] = useState<string>("");
  const [status, setStatus] = useState<"idle" | "submitting" | "done" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setStatus("submitting");
    setError(null);
    try {
      const response = await fetch("/api/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message,
          rating: rating ? Number(rating) : undefined,
          context,
          page,
        }),
      });
      const data = (await response.json().catch(() => ({}))) as { error?: string };
      if (!response.ok) {
        throw new Error(data.error || "Could not send feedback");
      }
      setStatus("done");
      setMessage("");
      setRating("");
    } catch (err) {
      setStatus("error");
      setError(err instanceof Error ? err.message : "Could not send feedback");
    }
  };

  if (status === "done") {
    return (
      <p className={cn("text-sm text-muted-foreground", className)}>
        Thanks — we got your feedback.
      </p>
    );
  }

  return (
    <form onSubmit={onSubmit} className={cn("space-y-4 text-left", className)}>
      <div>
        <p className="text-sm font-medium text-foreground">{heading}</p>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </div>
      <div className="space-y-2">
        <Label htmlFor={`feedback-rating-${context}`}>Rating (optional)</Label>
        <select
          id={`feedback-rating-${context}`}
          value={rating}
          onChange={(event) => setRating(event.target.value)}
          className="flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground"
        >
          <option value="">Skip</option>
          <option value="5">5 — loved it</option>
          <option value="4">4 — solid</option>
          <option value="3">3 — mixed</option>
          <option value="2">2 — rough</option>
          <option value="1">1 — broken</option>
        </select>
      </div>
      <div className="space-y-2">
        <Label htmlFor={`feedback-message-${context}`}>Message</Label>
        <textarea
          id={`feedback-message-${context}`}
          required
          minLength={3}
          maxLength={4000}
          rows={4}
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          placeholder="What question did you ask? What felt off?"
          className="flex w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        />
      </div>
      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      <Button type="submit" size="sm" disabled={status === "submitting"}>
        {status === "submitting" ? "Sending…" : "Send feedback"}
      </Button>
    </form>
  );
}
