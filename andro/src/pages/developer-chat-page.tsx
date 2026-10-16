import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Braces,
  FileCode2,
  GitCommitHorizontal,
  Search,
} from 'lucide-react';
import PromptBar from '@/components/PromptBar';
import ThoughtLine from '@/components/ThoughtLine';
import { parseScopedPrompt, sourceOptions } from '@/lib/promptScope';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { useTrialSession } from '@/hooks/useTrialSession';
import { useWorkspace } from '@/hooks/useWorkspace';
import {
  countBySourceType,
  searchAnswer,
  sourceTypeLabel,
  type CodeSearchHit,
} from '@/lib/andromediaApi';
import { cn } from '@/lib/utils';

/**
 * Developer-facing chat surface.
 *
 * Unlike the CRM investigation view, this queries `/api/v1/search` directly —
 * retrieval over the indexed corpus with no LLM in the path. That keeps it
 * fast, cheap, and deterministic: results are real code/ticket/document hits
 * with their paths and provenance, not a generated summary. The LLM-backed
 * "ask" flow stays available in the CRM view.
 */

const QUICK_PROMPTS = [
  { label: 'Symbol usages', query: 'usages of MetadataExtractorRegistry' },
  { label: 'Entry points', query: 'main entry point bootstrap application' },
  { label: 'Auth flow', query: 'authentication token verification' },
  { label: 'Config loading', query: 'configuration properties loader' },
  { label: 'Error handling', query: 'exception handling retry' },
  { label: 'Tests for a service', query: 'unit test service behaviour' },
] as const;

/** The same sample queries, reachable from the prompt bar's `/` menu. */
const DEV_COMMANDS = QUICK_PROMPTS.map((item) => {
  const slug = item.label.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return { key: slug, name: `/${slug}`, description: item.query };
});
const COMMAND_PROMPTS = new Map(
  DEV_COMMANDS.map((command, i) => [command.name, QUICK_PROMPTS[i].query]),
);

/** Where a query ran: the Andromedia index, or the live working tree. */
type SearchKind = 'index' | 'files';

interface FileMatch {
  path: string;
  line: number;
  text: string;
}

interface SearchMessage {
  id: string;
  query: string;
  kind: SearchKind;
  status: 'loading' | 'done' | 'error';
  /** Synthesized answer for index queries; absent when synthesis was unavailable. */
  answer?: string;
  answerGrounded?: boolean;
  hits: CodeSearchHit[];
  fileMatches: FileMatch[];
  fileTruncated?: boolean;
  fileEngine?: 'rg' | 'node';
  error?: string;
  elapsedMs: number | null;
}

/** Trim a long path to its trailing segments so cards stay scannable. */
function shortPath(path: string, maxSegments = 3): string {
  const parts = path.split('/').filter(Boolean);
  if (parts.length <= maxSegments) {
    return path;
  }
  return `…/${parts.slice(-maxSegments).join('/')}`;
}

/** Best-effort language label from a file extension, for the card header. */
function languageFromPath(path: string): string | null {
  const match = /\.([a-z0-9]+)$/i.exec(path);
  if (!match) {
    return null;
  }
  const map: Record<string, string> = {
    ts: 'TypeScript',
    tsx: 'TypeScript',
    js: 'JavaScript',
    jsx: 'JavaScript',
    py: 'Python',
    java: 'Java',
    kt: 'Kotlin',
    go: 'Go',
    rs: 'Rust',
    rb: 'Ruby',
    md: 'Markdown',
    yml: 'YAML',
    yaml: 'YAML',
    json: 'JSON',
    sql: 'SQL',
  };
  return map[match[1].toLowerCase()] ?? match[1].toLowerCase();
}

function ResultCard({ hit }: { hit: CodeSearchHit }) {
  const [expanded, setExpanded] = useState(false);
  const language = languageFromPath(hit.path);
  const isGit = (hit.sourceType ?? '').toUpperCase() === 'GIT';
  const content = hit.content ?? '';
  const trimmed = content.trim();
  const preview = expanded ? trimmed : trimmed.slice(0, 320);
  const clipped = !expanded && trimmed.length > preview.length;

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        {isGit ? (
          <GitCommitHorizontal className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        ) : (
          <FileCode2 className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        )}
        <span className="truncate font-mono text-[11px] text-foreground" title={hit.path}>
          {shortPath(hit.path)}
        </span>
        <span className="ml-auto flex shrink-0 items-center gap-2 text-[10px] text-muted-foreground">
          {language ? <span>{language}</span> : null}
          <span className="rounded border border-border px-1.5 py-0.5">
            {sourceTypeLabel(hit.sourceType)}
          </span>
          <span className="tabular-nums">{hit.score.toFixed(3)}</span>
        </span>
      </div>

      {trimmed ? (
        <pre className="overflow-x-auto px-3 py-2 font-mono text-[11px] leading-5 text-muted-foreground">
          {preview}
          {clipped ? '…' : ''}
        </pre>
      ) : null}

      {(clipped || expanded) && (
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="w-full cursor-pointer border-t border-border px-3 py-1.5 text-left text-[10px] text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground"
        >
          {expanded ? 'Show less' : 'Show more'}
        </button>
      )}
    </div>
  );
}


/**
 * One grep hit from the working tree.
 *
 * Clicking loads the surrounding lines through the same agent bridge, so the
 * user can read the file without leaving the app — and without the renderer
 * ever holding the agent's token.
 */
function FileMatchCard({ match }: { match: FileMatch }) {
  const [context, setContext] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = async () => {
    if (context || busy) return;
    setBusy(true);
    setErr(null);
    try {
      const result = await window.androDesktop!.files!.read({
        path: match.path,
        offset: Math.max(1, match.line - 6),
        limit: 14,
      });
      if (!result.ok) throw new Error(result.body.error ?? 'Could not read file');
      setContext(result.body.content ?? '');
    } catch (caught) {
      setErr(caught instanceof Error ? caught.message : 'Could not read file');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-lg border border-border bg-card">
      <button
        type="button"
        onClick={() => void load()}
        className="flex w-full cursor-pointer items-baseline gap-2 px-3 py-2 text-left"
      >
        <span className="truncate font-mono text-[11px] text-foreground" title={match.path}>
          {shortPath(match.path)}
        </span>
        <span className="shrink-0 font-mono text-[11px] text-muted-foreground">
          :{match.line}
        </span>
        <span className="ml-auto shrink-0 text-[10px] text-muted-foreground">
          {busy ? 'loading…' : context ? 'hide' : 'show context'}
        </span>
      </button>
      <pre className="overflow-x-auto border-t border-border px-3 py-2 font-mono text-[11px] leading-5 text-muted-foreground">
        {match.text.trim()}
      </pre>
      {err ? <p className="px-3 pb-2 text-[10px] text-destructive">{err}</p> : null}
      {context ? (
        <pre className="max-h-64 overflow-auto border-t border-border bg-muted/30 px-3 py-2 font-mono text-[11px] leading-5 text-foreground">
          {context}
        </pre>
      ) : null}
    </div>
  );
}

export function DeveloperChatPage() {
  const overview = useIndexOverview();
  const { isTrialReady, remaining } = useTrialSession();
  // Search must target the selected workspace; passing the trial service name
  // directly here would pin every query to 'local' and ignore the picker.
  const { active: activeWorkspace } = useWorkspace();
  // 'files' greps the live working tree via andro-agent; 'index' queries the
  // Andromedia index. Only offered in the desktop build, where the bridge and
  // the agent exist.
  const [mode, setMode] = useState<SearchKind>('index');
  // Offered only where the agent bridge exists; in the browser build the
  // working tree is unreachable, so the toggle is hidden rather than broken.
  const fileSearchAvailable = Boolean(window.androDesktop?.files);
  const [messages, setMessages] = useState<SearchMessage[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const sourceSummary = useMemo(() => {
    const types = overview.raw?.sourceTypes ?? [];
    if (types.length === 0) {
      return null;
    }
    return types.map((t) => sourceTypeLabel(t)).join(' · ');
  }, [overview.raw?.sourceTypes]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  const runSearch = useCallback(
    async (raw: string) => {
      const scoped = parseScopedPrompt(raw);
      const trimmed = COMMAND_PROMPTS.get(scoped.text) ?? scoped.text;
      const sourceTypes = scoped.sourceTypes.length > 0 ? scoped.sourceTypes : undefined;
      if (!trimmed || isSearching) {
        return;
      }
      const id = `s-${Date.now()}`;
      setMessages((prev) => [
        ...prev,
        {
          id,
          query: trimmed,
          kind: mode,
          status: 'loading',
          hits: [],
          fileMatches: [],
          elapsedMs: null,
        },
      ]);
      setIsSearching(true);
      const startedAt = performance.now();
      try {
        if (mode === 'files') {
          const result = await window.androDesktop!.files!.grep({
            pattern: trimmed,
            // Kept modest on purpose: asking for more results than the pattern
            // has forces a full-tree scan, which is slow when ripgrep is absent.
            max_results: 25,
          });
          if (!result.ok) {
            throw new Error(result.body.error ?? 'File search failed');
          }
          setMessages((prev) =>
            prev.map((m) =>
              m.id === id
                ? {
                    ...m,
                    status: 'done',
                    fileMatches: result.body.matches ?? [],
                    fileTruncated: result.body.truncated,
                    fileEngine: result.body.engine,
                    elapsedMs: Math.round(performance.now() - startedAt),
                  }
                : m,
            ),
          );
        } else {
          const response = await searchAnswer({
            query: trimmed,
            service: activeWorkspace ?? undefined,
            topK: 12,
            sourceTypes,
          });
          setMessages((prev) =>
            prev.map((m) =>
              m.id === id
                ? {
                    ...m,
                    status: 'done',
                    answer: response.answer,
                    answerGrounded: response.grounded,
                    hits: response.hits,
                    elapsedMs: Math.round(performance.now() - startedAt),
                  }
                : m,
            ),
          );
        }
      } catch (caught) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === id
              ? {
                  ...m,
                  status: 'error',
                  error: caught instanceof Error ? caught.message : 'Search failed',
                }
              : m,
          ),
        );
      } finally {
        setIsSearching(false);
      }
    },
    [isSearching, activeWorkspace, mode],
  );

  const indexReady = overview.raw?.exists ?? false;
  // `@` menu offers only the sources this workspace actually indexes.
  const devSources = sourceOptions(overview.raw?.sourceTypes ?? []);
  const placeholder =
    mode === 'files'
      ? 'Regex to search in your working tree…'
      : indexReady
        ? 'Search symbols, files, commits, tickets…'
        : 'Connect a source to build an index…';
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-3 px-6 py-3">
        <p className="truncate text-[11px] text-muted-foreground">
          {overview.loading
            ? 'checking index…'
            : indexReady
              ? `${overview.totalChunks.toLocaleString()} chunks · ${overview.sourcesConnected} source${overview.sourcesConnected === 1 ? '' : 's'}`
              : 'no index yet — connect a source to begin'}
        </p>
        {isTrialReady && remaining != null ? (
          <span className="ml-auto shrink-0 text-[11px] text-muted-foreground">
            trial · {remaining} left
          </span>
        ) : null}
      </div>

      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto px-6">
        {messages.length === 0 ? (
          <div className="mx-auto flex max-w-2xl flex-col items-center pt-16 text-center">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-border bg-card">
              <Braces className="h-5 w-5 text-muted-foreground" />
            </div>
            <h1 className="mt-4 text-base text-foreground">Search your codebase and tickets</h1>
            <p className="mt-2 max-w-md text-xs leading-relaxed text-muted-foreground">
              Retrieval runs directly against your local index — real files, commits, and records
              with paths and provenance. No model in the loop, so results are fast and repeatable.
            </p>
            {sourceSummary ? (
              <p className="mt-3 text-[11px] text-muted-foreground">Indexed: {sourceSummary}</p>
            ) : null}

            <div className="mt-6 flex flex-wrap justify-center gap-2">
              {QUICK_PROMPTS.map((prompt) => (
                <button
                  key={prompt.label}
                  type="button"
                  onClick={() => void runSearch(prompt.query)}
                  className="cursor-pointer rounded-full border border-border bg-muted/40 px-3 py-1.5 text-[11px] text-muted-foreground transition-colors hover:border-foreground/20 hover:bg-muted hover:text-foreground"
                >
                  {prompt.label}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="mx-auto max-w-3xl space-y-5 py-4">
            {messages.map((message) => {
              const counts = countBySourceType(message.hits);
              return (
                <div key={message.id} className="space-y-2">
                  <div className="flex items-center gap-2">
                    <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                    <p className="font-mono text-xs text-foreground">{message.query}</p>
                  </div>

                  {message.status === 'loading' && (
                    <div className="pl-5">
                      <ThoughtLine
                        working
                        label={
                          message.kind === 'files'
                            ? 'Searching your working tree…'
                            : 'Searching the index…'
                        }
                        fontSize={11}
                        color="var(--muted-foreground)"
                        collapsible={false}
                      />
                    </div>
                  )}

                  {message.status === 'error' && (
                    <p className="flex items-start gap-2 pl-5 text-[11px] text-destructive">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      {message.error}
                    </p>
                  )}

                  {message.status === 'done' && message.kind === 'files' && (
                    <div className="space-y-2 pl-5">
                      <p className="text-[11px] text-muted-foreground">
                        {message.fileMatches.length === 0
                          ? 'No matches in the working tree.'
                          : `${message.fileMatches.length} match${message.fileMatches.length === 1 ? '' : 'es'}` +
                            (message.fileTruncated ? ' (partial)' : '') +
                            (message.elapsedMs != null ? ` · ${message.elapsedMs} ms` : '')}
                      </p>
                      {message.fileMatches.map((m, i) => (
                        <FileMatchCard key={`${m.path}-${m.line}-${i}`} match={m} />
                      ))}
                      {message.fileEngine === 'node' ? (
                        <p className="text-[10px] text-muted-foreground">
                          Searched with the built-in fallback{message.fileTruncated ? ' and hit its time limit — results may be incomplete' : ''}.
                          Install ripgrep (<code className="text-[10px]">rg</code>) for faster, complete searches.
                        </p>
                      ) : null}
                    </div>
                  )}

                  {message.status === 'done' && message.kind === 'index' && (
                    <div className="space-y-2 pl-5">
                      {message.answer ? (
                        <div className="rounded-lg border border-border bg-card px-4 py-3">
                          <p className="whitespace-pre-wrap text-[13px] leading-6 text-foreground">
                            {message.answer}
                          </p>
                          {message.answerGrounded === false ? (
                            <p className="mt-2 text-[10px] text-muted-foreground">
                              Not grounded in the retrieved sources — treat as unverified.
                            </p>
                          ) : null}
                        </div>
                      ) : null}
                      <p className="text-[11px] text-muted-foreground">
                        {message.hits.length === 0
                          ? 'No matches in the index.'
                          : `Evidence · ${message.hits.length} source${message.hits.length === 1 ? '' : 's'}` +
                            (Object.keys(counts).length > 1
                              ? ` · ${Object.entries(counts)
                                  .map(([type, n]) => `${n} ${sourceTypeLabel(type)}`)
                                  .join(', ')}`
                              : '') +
                            (message.elapsedMs != null ? ` · ${message.elapsedMs} ms` : '')}
                      </p>
                      {message.hits.map((hit) => (
                        <ResultCard key={hit.chunkId} hit={hit} />
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      <div className="px-6 pb-6 pt-2">
        <div className="mx-auto max-w-3xl">
          <PromptBar
            width={768}
            placeholder={placeholder}
            busy={isSearching}
            sources={devSources}
            commands={DEV_COMMANDS}
            models={[]}
            efforts={[]}
            onSend={(text) => void runSearch(text)}
            background="var(--muted)"
            color="var(--foreground)"
            menuBackground="var(--card)"
            sparkColor="var(--primary)"
            radius={10}
            maxRows={4}
          />
          <div className="mt-2 flex items-center justify-center gap-2">
            {fileSearchAvailable ? (
              <div
                role="group"
                aria-label="Search source"
                className="inline-flex overflow-hidden rounded-full border border-border"
              >
                {(
                  [
                    ['index', 'Index'],
                    ['files', 'Working tree'],
                  ] as const
                ).map(([value, label]) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => setMode(value)}
                    aria-pressed={mode === value}
                    className={cn(
                      'cursor-pointer px-2.5 py-0.5 text-[10px] transition-colors',
                      mode === value
                        ? 'bg-muted text-foreground'
                        : 'text-muted-foreground hover:text-foreground',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
          <p className="mt-2 text-center text-[10px] text-muted-foreground">
            {mode === 'files'
              ? 'Searches your working tree via Andro Agent · read-only, workspace-confined'
              : 'Runs against your local index · results are retrievals, not generated text'}
          </p>
        </div>
      </div>
    </div>
  );
}
