import { Copy, Check } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

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
    <div className={cn('flex gap-2', className)}>
      <pre className="flex-1 overflow-x-auto rounded-md border border-border bg-background p-3 text-[10px] text-muted-foreground font-mono leading-relaxed">
        {value}
      </pre>
      <Button type="button" variant="outline" size="sm" onClick={handleCopy} className="shrink-0 self-start">
        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
      </Button>
    </div>
  );
}
