import { useEffect, useRef, useState } from 'react';
import { Check, ChevronDown, FolderOpen, FolderPlus, LoaderCircle } from 'lucide-react';
import { useWorkspace } from '@/hooks/useWorkspace';
import { sourceTypeLabel } from '@/lib/andromediaApi';
import { AddProjectDialog } from '@/components/AddProjectDialog';
import { cn } from '@/lib/utils';

/**
 * Workspace switcher.
 *
 * A workspace is one indexed project. Selecting one retargets every subsequent
 * API call (see `setActiveWorkspace`), so this is the app's primary context
 * control — which is why it sits at the top of the sidebar rather than buried
 * in settings.
 */
export function WorkspacePicker() {
  const { workspaces, active, loading, error, locked, select, refresh } = useWorkspace();
  const [open, setOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Close on outside click / Escape, matching standard menu behaviour.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const current = workspaces.find((w) => w.service === active);
  const label = active ?? 'no workspace';

  return (
    <div ref={containerRef} className="relative px-2 pb-1 pt-2">
      <p className="px-1 pb-1.5 text-[10px] uppercase tracking-wider text-muted-foreground">
        Workspace
      </p>

      <button
        type="button"
        onClick={() => {
          if (locked) return;
          setOpen((v) => !v);
          if (!open) void refresh();
        }}
        disabled={locked}
        aria-haspopup="listbox"
        aria-expanded={open}
        title={locked ? 'Trial mode uses its own workspace' : 'Switch workspace'}
        className={cn(
          'flex w-full items-center gap-2 rounded-md border border-border bg-card px-2.5 py-2 text-left transition-colors',
          locked ? 'cursor-default opacity-70' : 'cursor-pointer hover:border-foreground/20',
        )}
      >
        <FolderOpen className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[12px] text-foreground">{label}</span>
          <span className="block truncate text-[10px] text-muted-foreground">
            {loading && !current
              ? 'loading…'
              : current
                ? `${current.totalChunks.toLocaleString()} chunks`
                : 'none indexed yet'}
          </span>
        </span>
        {loading ? (
          <LoaderCircle className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
        ) : (
          <ChevronDown
            className={cn(
              'h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform',
              open && 'rotate-180',
            )}
          />
        )}
      </button>

      {open ? (
        <div
          role="listbox"
          className="absolute left-2 right-2 z-30 mt-1 max-h-72 overflow-y-auto rounded-md border border-border bg-card shadow-lg"
        >
          {workspaces.length === 0 ? (
            <p className="px-2.5 py-3 text-[11px] text-muted-foreground">
              No indexed projects yet. Index a repository to get started.
            </p>
          ) : (
            workspaces.map((workspace) => {
              const isActive = workspace.service === active;
              return (
                <button
                  key={workspace.service}
                  type="button"
                  role="option"
                  aria-selected={isActive}
                  onClick={() => {
                    select(workspace.service);
                    setOpen(false);
                  }}
                  className={cn(
                    'flex w-full cursor-pointer items-start gap-2 px-2.5 py-2 text-left transition-colors',
                    isActive ? 'bg-muted' : 'hover:bg-muted/60',
                  )}
                >
                  <Check
                    className={cn(
                      'mt-0.5 h-3 w-3 shrink-0',
                      isActive ? 'text-primary' : 'text-transparent',
                    )}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[12px] text-foreground">
                      {workspace.service}
                    </span>
                    <span className="block truncate text-[10px] text-muted-foreground">
                      {workspace.exists
                        ? `${workspace.totalChunks.toLocaleString()} chunks`
                        : 'empty'}
                      {workspace.sourceTypes?.length
                        ? ` · ${workspace.sourceTypes
                            .slice(0, 3)
                            .map((t) => sourceTypeLabel(t))
                            .join(', ')}${workspace.sourceTypes.length > 3 ? '…' : ''}`
                        : ''}
                    </span>
                  </span>
                </button>
              );
            })
          )}

          {/* Indexing is a local-mode action; trial users have no host repos. */}
          {!locked ? (
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                setAddOpen(true);
              }}
              className="flex w-full cursor-pointer items-center gap-2 border-t border-border px-2.5 py-2 text-left text-[12px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
            >
              <FolderPlus className="h-3 w-3 shrink-0" />
              Index a project…
            </button>
          ) : null}
        </div>
      ) : null}

      {error ? <p className="px-1 pt-1 text-[10px] text-destructive">{error}</p> : null}

      <AddProjectDialog
        open={addOpen}
        onClose={() => setAddOpen(false)}
        onIndexed={(service) => {
          void refresh().then(() => select(service));
        }}
      />
    </div>
  );
}
