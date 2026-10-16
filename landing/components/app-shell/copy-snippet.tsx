"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface CopySnippetProps {
  value: string;
  className?: string;
}

export function CopySnippet({ value, className }: CopySnippetProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className={cn("flex gap-2", className)}>
      <pre className="flex-1 overflow-x-auto rounded-md border border-border bg-background p-3 font-mono text-[10px] leading-relaxed text-muted-foreground">
        {value}
      </pre>
      <Button type="button" variant="outline" size="sm" onClick={handleCopy} className="shrink-0 self-start">
        {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      </Button>
    </div>
  );
}
