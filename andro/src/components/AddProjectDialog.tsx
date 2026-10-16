import { useEffect, useMemo, useState } from 'react';
import { FolderPlus, LoaderCircle, TriangleAlert } from 'lucide-react';
import {
  Dialog,
  DialogCloseButton,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  fetchWorkspaceCandidates,
  pollCodeIndexJob,
  startCodeIndex,
  type WorkspaceCandidate,
} from '@/lib/andromediaApi';
import { cn } from '@/lib/utils';

/**
 * Index a project that lives under the workspace mount.
 *
 * The API runs in a container and can only read what is mounted into it, so
 * this offers the projects it can actually see rather than a host folder
 * picker — offering the latter would let a request name any path the process
 * can reach.
 */
export function AddProjectDialog({
  open,
  onClose,
  onIndexed,
}: {
  open: boolean;
  onClose: () => void;
  onIndexed: (service: string) => void;
}) {
  const [candidates, setCandidates] = useState<WorkspaceCandidate[]>([]);
  const [selected, setSelected] = useState<WorkspaceCandidate | null>(null);
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<'idle' | 'indexing'>('idle');
  const [progress, setProgress] = useState<string>('');

  useEffect(() => {
    if (!open) {
      return;
    }
    setError(null);
    setPhase('idle');
    setProgress('');
    setSelected(null);
    setName('');
    setLoading(true);
    void fetchWorkspaceCandidates()
      .then(setCandidates)
      .catch((caught) =>
        setError(caught instanceof Error ? caught.message : 'Could not list projects'),
      )
      .finally(() => setLoading(false));
  }, [open]);

  // A workspace name becomes a directory name and a request parameter, so keep
  // it to characters the backend's sanitizer will not rewrite.
  const nameError = useMemo(() => {
    if (!name.trim()) return null;
    return /^[a-zA-Z0-9._-]+$/.test(name.trim())
      ? null
      : 'Use letters, numbers, dot, dash or underscore only';
  }, [name]);

  const canIndex = Boolean(selected) && Boolean(name.trim()) && !nameError && phase === 'idle';

  const run = async () => {
    if (!selected || !canIndex) return;
    const service = name.trim();
    setPhase('indexing');
    setError(null);
    setProgress('starting…');
    try {
      const job = await startCodeIndex({ service, localPath: selected.path });
      const finished = await pollCodeIndexJob(job.jobId, {
        intervalMs: 1200,
        timeoutMs: 30 * 60 * 1000,
      });
      if (finished.status === 'failed') {
        throw new Error(finished.error ?? 'Indexing failed');
      }
      onIndexed(service);
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Indexing failed');
      setPhase('idle');
      setProgress('');
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent
        className="flex max-h-[80vh] w-[min(92vw,40rem)] flex-col gap-0 overflow-hidden p-0"
        aria-describedby={undefined}
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border px-5 py-4">
          <DialogTitle className="text-sm text-foreground">Index a project</DialogTitle>
          <DialogCloseButton />
        </div>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5">
          {loading ? (
            <p className="flex items-center gap-2 text-xs text-muted-foreground">
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
              Looking for projects…
            </p>
          ) : candidates.length === 0 ? (
            <div className="flex items-start gap-2 rounded-md border border-border bg-muted/30 px-3 py-2.5">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              <div className="text-[11px] leading-relaxed text-muted-foreground">
                <p>No projects found.</p>
                <p className="mt-1">
                  The API can only index what is mounted into it. Set{' '}
                  <code className="text-[10px]">ANDRO_WORKSPACE_DIR</code> to the folder holding
                  your repositories (for example <code className="text-[10px]">~/Documents/GitHub</code>
                  ) and restart the API.
                </p>
              </div>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label className="text-xs">Project</Label>
              <div className="max-h-64 overflow-y-auto rounded-md border border-border">
                {candidates.map((candidate) => {
                  const isSelected = selected?.path === candidate.path;
                  return (
                    <button
                      key={candidate.path}
                      type="button"
                      onClick={() => {
                        setSelected(candidate);
                        setName(candidate.name);
                      }}
                      disabled={phase === 'indexing'}
                      className={cn(
                        'flex w-full cursor-pointer flex-col items-start border-b border-border px-3 py-2 text-left last:border-b-0 transition-colors',
                        isSelected ? 'bg-muted' : 'hover:bg-muted/60',
                      )}
                    >
                      <span className="text-[12px] text-foreground">{candidate.name}</span>
                      <span className="truncate font-mono text-[10px] text-muted-foreground">
                        {candidate.markers.join(', ')}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div className="space-y-1.5">
            <Label className="text-xs">Workspace name</Label>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="e.g. payments-api"
              disabled={phase === 'indexing'}
              autoComplete="off"
            />
            {nameError ? (
              <p className="text-[11px] text-destructive">{nameError}</p>
            ) : (
              <p className="text-[11px] text-muted-foreground">
                How this project appears in the workspace picker.
              </p>
            )}
          </div>

          {phase === 'indexing' ? (
            <p className="flex items-center gap-2 text-[11px] text-muted-foreground">
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
              Indexing {selected?.name}… {progress}
            </p>
          ) : null}

          {error ? <p className="text-[11px] text-destructive">{error}</p> : null}
        </div>

        <div className="flex shrink-0 items-center justify-end gap-2 border-t border-border px-5 py-3">
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" size="sm" onClick={() => void run()} disabled={!canIndex}>
            <FolderPlus className="h-3.5 w-3.5" />
            {phase === 'indexing' ? 'Indexing…' : 'Index project'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
