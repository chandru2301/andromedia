import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import type { SourceFilterOption, SourceId } from '@/types/config';
import { cn } from '@/lib/utils';

interface SourceFilterCheckboxesProps {
  sources: SourceFilterOption[];
  values: Partial<Record<SourceId, boolean>>;
  onChange: (source: SourceId, enabled: boolean) => void;
}

export function SourceFilterCheckboxes({
  sources,
  values,
  onChange,
}: SourceFilterCheckboxesProps) {
  if (sources.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        No sources available yet. Index a connector in Configure to populate live types.
      </p>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      {sources.map((source) => (
        <div key={source.id} className="flex items-start gap-2">
          <Checkbox
            id={`source-${source.id}`}
            checked={values[source.id] === true}
            onCheckedChange={(checked) => onChange(source.id, checked === true)}
            className="mt-0.5"
          />
          <div className="min-w-0">
            <Label
              htmlFor={`source-${source.id}`}
              className={cn(
                'cursor-pointer text-sm',
                source.indexed ? 'text-foreground' : 'text-muted-foreground',
              )}
            >
              {source.label}
            </Label>
            {source.hint ? (
              <p className="text-[10px] leading-tight text-muted-foreground">{source.hint}</p>
            ) : null}
          </div>
        </div>
      ))}
    </div>
  );
}
