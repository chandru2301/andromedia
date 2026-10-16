import { useEffect, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ChevronUp,
  Database,
  Info,
  PanelRightClose,
  PanelRightOpen,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import PromptBar from '@/components/PromptBar';
import ThoughtLine from '@/components/ThoughtLine';
import { ProfileMenu } from '@/components/layout/ProfileMenu';
import { parseScopedPrompt, sourceOptions } from '@/lib/promptScope';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import {
  investigateCrmStream,
  TrialLimitError,
  type InvestigationAnswer,
  type InvestigationEvidence,
  type InvestigationProgressEvent,
  type InvestigationResponse,
} from '@/lib/andromediaApi';
import { useTrialSession } from '@/hooks/useTrialSession';

const QUICK_QUERIES = [
  {
    label: 'most qualified deal',
    query: 'What is the most qualified active deal?',
  },
  {
    label: 'deal size at King',
    query: "What's the deal size for ERP Implementation at King?",
  },
  {
    label: 'King account',
    query: "What's going on with the King account?",
  },
  {
    label: 'stale deals',
    query: 'Which deals are stale or overdue?',
  },
  {
    label: 'TechNova contacts',
    query: 'What contacts do we have at TechNova?',
  },
] as const;

/** The same quick questions, reachable from the prompt bar's `/` menu. */
const CRM_COMMANDS = QUICK_QUERIES.map((item) => {
  const slug = item.label.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return { key: slug, name: `/${slug}`, description: item.query };
});
const COMMAND_PROMPTS = new Map(
  CRM_COMMANDS.map((command, i) => [command.name, QUICK_QUERIES[i].query]),
);

interface ProgressRow {
  key: string;
  step: string;
  status: 'running' | 'done';
  detail?: string;
  sourceType?: string;
}

function stepFallbackLabel(step: string): string {
  return step
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

/** Append-only activity transcript from real SSE progress events. */
function deriveProgressRows(events: InvestigationProgressEvent[]): ProgressRow[] {
  const rows: ProgressRow[] = [];
  for (const event of events) {
    if (event.type !== 'progress' || !event.step) {
      continue;
    }
    rows.push({
      key: `${event.step}-${rows.length}`,
      step: event.step,
      status: event.status ?? 'running',
      detail: event.detail,
      sourceType: event.sourceType,
    });
  }
  return rows;
}

function formatStructuredValue(key: string, value: string): string {
  if ((key === 'Amount' || key === 'Expected_Revenue') && /^-?\d+(\.\d+)?$/.test(value)) {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      maximumFractionDigits: 0,
    }).format(Number(value));
  }
  if (key === 'Probability' && /^-?\d+(\.\d+)?$/.test(value)) {
    return `${value}%`;
  }
  if ((key === 'Modified' || key === 'Close Date' || key === 'Close') && value.length >= 10) {
    return value.slice(0, 10);
  }
  return value;
}

function fieldValue(
  fields: Record<string, string> | undefined,
  ...keys: string[]
): string {
  if (!fields) {
    return '—';
  }
  for (const key of keys) {
    const value = fields[key];
    if (value != null && value.trim() !== '') {
      return value;
    }
  }
  return '—';
}

function normalizeDealName(name: string | null | undefined): string {
  return (name ?? '').trim().toLowerCase();
}

/** Prefer the first exact primary name when the backend joins multiple with commas. */
function primaryDealNames(recordName: string | null | undefined): string[] {
  if (!recordName || !recordName.trim()) {
    return [];
  }
  return recordName
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

/** Internal signal names must never reach a buyer's screen as-is. */
const FLAG_LABELS: Record<string, string> = {
  test_demo_pattern: 'Sample data',
  stale_record: 'Not updated recently',
  close_date_passed: 'Close date passed',
  missing_account_description: 'No account description',
  no_contacts: 'No contacts',
  no_notes: 'No notes',
  no_activities: 'No activity logged',
  unknown_stage: 'Stage not set',
  possible_account_mention_unconfirmed_deal: 'Account mentioned, deal unconfirmed',
};

function humanizeFlag(flag: string): string {
  return FLAG_LABELS[flag] ?? flag.replaceAll('_', ' ');
}

function flagTone(flag: string): string {
  if (flag === 'test_demo_pattern' || flag === 'stale_record') {
    return 'border-destructive/40 bg-destructive/10 text-destructive';
  }
  if (
    flag === 'close_date_passed' ||
    flag === 'missing_account_description' ||
    flag === 'unknown_stage'
  ) {
    return 'border-warning/40 bg-warning/10 text-warning';
  }
  return 'border-border bg-muted/60 text-muted-foreground';
}

function EvidenceDetails({ evidence }: { evidence: InvestigationEvidence }) {
  return (
    <details className="relative">
      <summary className="cursor-pointer truncate text-[11px] text-primary hover:underline">
        View
      </summary>
      <div className="absolute right-0 z-20 mt-2 w-64 max-w-[min(16rem,70vw)] space-y-3 rounded-md border border-border bg-background p-3 shadow-md">
        {evidence.flags.length > 0 && (
          <section>
            <h4 className="mb-1 text-[10px] uppercase tracking-[0.14em] text-warning">Flags</h4>
            <div className="flex flex-wrap gap-2">
              {evidence.flags.map((flag) => (
                <span
                  key={flag}
                  className={`rounded border px-2.5 py-1 text-[11px] font-medium ${flagTone(flag)}`}
                >
                  {humanizeFlag(flag)}
                </span>
              ))}
            </div>
          </section>
        )}

        {evidence.contacts.length > 0 && (
          <section>
            <h4 className="mb-1 text-[10px] uppercase tracking-[0.14em] text-primary">Contacts</h4>
            <div className="flex flex-wrap gap-2">
              {evidence.contacts.map((contact) => (
                <span
                  key={contact}
                  className="rounded border border-primary/30 bg-primary/10 px-2.5 py-1 text-xs text-primary"
                >
                  {contact}
                </span>
              ))}
            </div>
          </section>
        )}

        {evidence.notes.length > 0 && (
          <section>
            <h4 className="mb-1 text-[10px] uppercase tracking-[0.14em] text-muted-foreground">
              Notes — Unverified free-text
            </h4>
            <div className="space-y-2">
              {evidence.notes.map((note, index) => (
                <div
                  key={`${note}-${index}`}
                  className="rounded-r-md border-l-2 border-warning bg-muted/40 px-3 py-2"
                >
                  <p className="text-xs leading-5 text-foreground">“{note}”</p>
                  <p className="mt-1 text-[10px] text-muted-foreground">
                    Deal note · Unverified free-text · not a structured CRM field
                  </p>
                </div>
              ))}
            </div>
          </section>
        )}

        <section className="border-t border-border pt-2 text-[10px] text-muted-foreground">
          <p>Retrieval score: {evidence.score.toFixed(3)}</p>
          {evidence.narrative && (
            <p className="mt-1 max-h-24 overflow-hidden whitespace-pre-wrap leading-4">
              {evidence.narrative}
            </p>
          )}
        </section>
      </div>
    </details>
  );
}

/** Widths sum to 100% for stable table-fixed layout. */
const EVIDENCE_COLUMNS = [
  { heading: 'Deal', width: '24%', align: 'left' as const },
  { heading: 'Probability', width: '12%', align: 'right' as const },
  { heading: 'Stage', width: '14%', align: 'left' as const },
  { heading: 'Amount', width: '12%', align: 'right' as const },
  { heading: 'Close date', width: '11%', align: 'right' as const },
  { heading: 'Modified', width: '11%', align: 'right' as const },
  { heading: 'Account', width: '10%', align: 'left' as const },
  { heading: 'Details', width: '6%', align: 'left' as const },
] as const;

function EvidenceTable({
  evidence,
  selectedName,
}: {
  evidence: InvestigationEvidence[];
  selectedName?: string | null;
}) {
  const selectedKey = normalizeDealName(selectedName);

  return (
    <div className="overflow-x-auto px-2 py-1">
      <table className="w-full min-w-[44rem] table-fixed border-collapse text-left">
        <colgroup>
          {EVIDENCE_COLUMNS.map((column) => (
            <col key={column.heading} style={{ width: column.width }} />
          ))}
        </colgroup>
        <thead>
          <tr className="border-b border-border bg-muted/40">
            {EVIDENCE_COLUMNS.map((column) => (
              <th
                key={column.heading}
                scope="col"
                className={`truncate px-2 py-2 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground ${
                  column.align === 'right' ? 'text-right' : 'text-left'
                }`}
              >
                {column.heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {evidence.map((item, index) => {
            const isSelected =
              selectedKey.length > 0 && normalizeDealName(item.name) === selectedKey;
            return (
              <tr
                key={`${item.source}-${item.name}-${index}`}
                className={`border-b border-border last:border-0 ${
                  isSelected ? 'bg-primary/5' : ''
                }`}
              >
                <th
                  scope="row"
                  title={item.name}
                  className={`truncate px-2 py-3 text-left text-xs font-medium align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {item.name}
                  {isSelected && (
                    <span className="ml-2 text-[10px] font-normal uppercase tracking-[0.12em] text-primary">
                      Top
                    </span>
                  )}
                </th>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {formatStructuredValue('Probability', item.structured.Probability ?? '—')}
                </td>
                <td
                  title={item.structured.Stage ?? undefined}
                  className={`truncate px-2 py-3 text-left text-xs align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {item.structured.Stage ?? '—'}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {formatStructuredValue('Amount', item.structured.Amount ?? '—')}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {formatStructuredValue(
                    'Close Date',
                    item.structured['Close Date'] ?? item.structured.Close ?? '—',
                  )}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {formatStructuredValue('Modified', item.structured.Modified ?? '—')}
                </td>
                <td
                  title={item.structured.Account ?? undefined}
                  className={`truncate px-2 py-3 text-left text-xs align-middle ${
                    isSelected ? 'text-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {item.structured.Account ?? '—'}
                </td>
                <td className="overflow-visible px-2 py-3 align-middle">
                  <EvidenceDetails evidence={item} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function TopResultPanel({
  answer,
  matchedEvidence,
}: {
  answer: InvestigationAnswer;
  matchedEvidence: InvestigationEvidence | null;
}) {
  const fields = answer.primaryFields ?? {};
  const displayName =
    primaryDealNames(answer.recordName)[0] ?? answer.recordName ?? 'Selected deal';

  const probability = fieldValue(
    matchedEvidence?.structured ?? fields,
    'Probability',
  );
  const stage = fieldValue(matchedEvidence?.structured ?? fields, 'Stage');
  const amount = fieldValue(matchedEvidence?.structured ?? fields, 'Amount');
  const closeDate = fieldValue(
    matchedEvidence?.structured ?? fields,
    'Close Date',
    'Close',
  );
  const modified = fieldValue(matchedEvidence?.structured ?? fields, 'Modified');
  const account = fieldValue(matchedEvidence?.structured ?? fields, 'Account');

  const metrics = [
    { label: 'Probability', value: formatStructuredValue('Probability', probability) },
    { label: 'Stage', value: stage },
    { label: 'Amount', value: formatStructuredValue('Amount', amount) },
    { label: 'Close date', value: formatStructuredValue('Close Date', closeDate) },
    { label: 'Modified', value: formatStructuredValue('Modified', modified) },
    { label: 'Account', value: account },
  ] as const;

  return (
    <article className="rounded-xl border border-primary/40 bg-card px-5 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[10px] font-medium uppercase tracking-[0.16em] text-primary">
            Top result
          </p>
          <h2 className="mt-1 truncate text-base font-semibold text-foreground" title={displayName}>
            {displayName}
          </h2>
        </div>
        {matchedEvidence && matchedEvidence.flags.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {matchedEvidence.flags.slice(0, 3).map((flag) => (
              <span
                key={flag}
                className={`rounded border px-2 py-0.5 text-[10px] font-medium ${flagTone(flag)}`}
              >
                {humanizeFlag(flag)}
              </span>
            ))}
          </div>
        )}
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3 lg:grid-cols-6">
        {metrics.map((metric) => (
          <div key={metric.label} className="min-w-0">
            <dt className="text-[10px] uppercase tracking-[0.12em] text-muted-foreground">
              {metric.label}
            </dt>
            <dd
              title={metric.value}
              className={`mt-1 truncate text-xs text-foreground ${
                metric.label === 'Stage' || metric.label === 'Account'
                  ? 'text-left'
                  : 'tabular-nums'
              }`}
            >
              {metric.value}
            </dd>
          </div>
        ))}
      </dl>

      {matchedEvidence && matchedEvidence.contacts.length > 0 && (
        <div className="mt-4 flex flex-wrap gap-2 border-t border-border pt-3">
          {matchedEvidence.contacts.slice(0, 4).map((contact) => (
            <span
              key={contact}
              className="rounded border border-primary/30 bg-primary/10 px-2.5 py-1 text-[11px] text-primary"
            >
              {contact}
            </span>
          ))}
        </div>
      )}
    </article>
  );
}

function AnswerCard({
  answer,
}: {
  answer: InvestigationAnswer | null | undefined;
}) {
  const reasons = answer?.reasons ?? [];

  return (
    <article>
      <p className="whitespace-pre-wrap text-sm leading-6 text-foreground">
        {answer?.summaryText || 'No synthesized answer was returned.'}
      </p>

      {reasons.length > 0 && (
        <div className="mt-5 border-t border-border pt-4">
          <h3 className="mb-2 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
            Why this answer?
          </h3>
          <ul className="space-y-1.5">
            {reasons.map((reason) => (
              <li key={reason} className="flex gap-2 text-xs leading-5 text-muted-foreground">
                <Check className="mt-0.5 h-3 w-3 shrink-0 text-muted-foreground/60" />
                <span>{reason}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </article>
  );
}

interface ChatMessage {
  id: string;
  query: string;
  status: 'loading' | 'done' | 'error';
  result?: InvestigationResponse;
  error?: string;
  progressEvents?: InvestigationProgressEvent[];
  startedAt?: number;
  settledAt?: number;
}

function isZohoCrmEvidence(item: InvestigationEvidence): boolean {
  return (item.source ?? '').toUpperCase() === 'ZOHO';
}

function SupportingEvidenceList({ evidence }: { evidence: InvestigationEvidence[] }) {
  if (evidence.length === 0) {
    return null;
  }
  return (
    <ul className="divide-y divide-border">
      {evidence.map((item, index) => {
        const snippet = (item.narrative || item.notes?.[0] || '').replace(/\s+/g, ' ').trim();
        const clipped = snippet.length > 220 ? `${snippet.slice(0, 220)}…` : snippet;
        return (
          <li
            key={`${item.source}-${item.name}-${index}`}
            className="flex flex-col gap-1 px-4 py-3"
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded border border-border bg-muted/40 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                {item.source || 'source'}
              </span>
              <span className="truncate text-xs font-medium text-foreground" title={item.name}>
                {item.name || '(unnamed)'}
              </span>
            </div>
            {clipped ? (
              <p className="text-[11px] leading-5 text-muted-foreground">{clipped}</p>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}

function evidenceForResult(result: InvestigationResponse | undefined) {
  const evidence = result?.evidence ?? [];
  const referenceCount = evidence.length > 0 ? evidence.length : (result?.metadata.referenceCount ?? 0);

  // A conflict-adjudicated answer is grounded in a real record too — it just also had to
  // reconcile a disagreement between sources, so it must render with the same primary card.
  const hasStructuredPrimary =
    (result?.answer?.trustState === 'structured' ||
      result?.answer?.trustState === 'conflict_adjudicated') &&
    Boolean(result.answer.recordName?.trim()) &&
    Object.keys(result.answer.primaryFields ?? {}).length > 0;

  const primaryNames = primaryDealNames(result?.answer?.recordName);
  const primaryNameKey = normalizeDealName(primaryNames[0]);
  const primaryAccountKey = normalizeDealName(
    result?.answer?.primaryFields?.Account ?? result?.answer?.primaryFields?.account,
  );

  let matchedPrimaryEvidence: InvestigationEvidence | null = null;
  if (primaryNameKey) {
    const nameMatches = evidence.filter(
      (item) => normalizeDealName(item.name) === primaryNameKey,
    );
    if (nameMatches.length > 0) {
      matchedPrimaryEvidence =
        (primaryAccountKey
          ? nameMatches.find(
              (item) =>
                normalizeDealName(item.structured.Account ?? item.structured.account) ===
                primaryAccountKey,
            )
          : undefined) ?? nameMatches[0];
    }
  }

  const supportingEvidence =
    hasStructuredPrimary && matchedPrimaryEvidence
      ? evidence.filter((item) => item !== matchedPrimaryEvidence)
      : evidence;

  const crmEvidence = supportingEvidence.filter(isZohoCrmEvidence);
  const narrativeEvidence = supportingEvidence.filter((item) => !isZohoCrmEvidence(item));

  return {
    evidence,
    referenceCount,
    hasStructuredPrimary,
    primaryNames,
    matchedPrimaryEvidence,
    supportingEvidence,
    crmEvidence,
    narrativeEvidence,
  };
}

function useElapsedMs(startedAt: number | undefined, active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active || startedAt == null) {
      return;
    }
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [active, startedAt]);
  if (startedAt == null) {
    return 0;
  }
  return Math.max(0, now - startedAt);
}

/** First-person phrasing so backend step narration reads like the model thinking out loud. */
function thoughtText(row: ProgressRow | undefined): string {
  if (!row) {
    return 'Reading your question…';
  }
  const detail = row.detail?.trim();
  if (detail) {
    return detail;
  }
  return `${stepFallbackLabel(row.step)}…`;
}

/**
 * The response "thinking" line. While working, the current thought breathes and
 * shimmers above a live trace of the steps; once settled it folds into
 * "Thought for X.Xs" and can be expanded again to read the trail.
 */
function ThoughtTrail({
  rows,
  startedAt,
  settledAt,
  status,
  fontSize = 13,
}: {
  rows: ProgressRow[];
  startedAt?: number;
  settledAt?: number;
  status: 'loading' | 'done' | 'error';
  fontSize?: number;
}) {
  const working = status === 'loading';
  const liveMs = useElapsedMs(startedAt, working);
  const all = rows.map((row) => thoughtText(row));
  const label = all[all.length - 1] ?? 'Reading your question…';
  // While working the current step *is* the headline, so the trace carries only
  // the trail behind it. Once settled the headline becomes "Thought for …" and
  // the whole trail belongs in the (collapsed) trace.
  const steps = working ? all.slice(0, -1) : all;
  const elapsed =
    startedAt == null
      ? undefined
      : Math.max(0, (working ? liveMs : (settledAt ?? startedAt) - startedAt) / 1000);

  return (
    <ThoughtLine
      working={working}
      steps={steps}
      label={label}
      doneLabel="Thought for"
      elapsed={elapsed}
      fontSize={fontSize}
      color="var(--foreground)"
      collapsible
      collapseOnSettle
      showTimer
    />
  );
}

/** Live SSE activity, rendered as one texting-style bubble that updates in place — no step list. */
function LiveActivityPanel({
  events,
  startedAt,
  compact = false,
}: {
  events: InvestigationProgressEvent[];
  startedAt?: number;
  compact?: boolean;
}) {
  const rows = deriveProgressRows(events);

  return (
    <div
      className={compact ? '' : 'rounded-xl border border-border bg-card px-4 py-3.5'}
      aria-live="polite"
      aria-busy={true}
    >
      <ThoughtTrail rows={rows} startedAt={startedAt} status="loading" />
    </div>
  );
}

/** Chat bubble shows only the current thought — the full trail lives in the right panel. */
function LoadingAssistantBody({ message }: { message: ChatMessage }) {
  const rows = deriveProgressRows(message.progressEvents ?? []);
  return (
    <ThoughtTrail
      rows={rows}
      startedAt={message.startedAt}
      settledAt={message.settledAt}
      status={message.status}
    />
  );
}

function RightPanel({ message }: { message: ChatMessage | undefined }) {
  const [showAllEvidence, setShowAllEvidence] = useState(true);

  useEffect(() => {
    setShowAllEvidence(true);
  }, [message?.id]);

  if (!message) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
        <Database className="h-5 w-5 text-muted-foreground/50" />
        <p className="text-xs text-muted-foreground">
          Evidence and structured results for your questions will appear here.
        </p>
      </div>
    );
  }

  if (message.status === 'loading') {
    return (
      <div className="px-4 py-4">
        <LiveActivityPanel events={message.progressEvents ?? []} startedAt={message.startedAt} />
      </div>
    );
  }

  if (message.status === 'error') {
    return (
      <div className="px-4 py-4">
        <section className="flex items-start gap-3 rounded-xl border border-destructive/40 bg-destructive/5 px-4 py-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
          <div>
            <h2 className="text-xs font-medium text-destructive">Investigation failed</h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">{message.error}</p>
          </div>
        </section>
      </div>
    );
  }

  const {
    evidence,
    referenceCount,
    hasStructuredPrimary,
    primaryNames,
    matchedPrimaryEvidence,
    crmEvidence,
    narrativeEvidence,
  } = evidenceForResult(message.result);

  const displayCount = crmEvidence.length + narrativeEvidence.length;

  return (
    <div className="space-y-3 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-3 py-2.5 text-[11px] text-muted-foreground">
        <Database className="h-3.5 w-3.5 shrink-0 text-muted-foreground/70" />
        <p>{`Grounded against ${referenceCount} indexed reference${referenceCount === 1 ? '' : 's'}`}</p>
      </div>

      {hasStructuredPrimary && message.result?.answer && (
        <TopResultPanel answer={message.result.answer} matchedEvidence={matchedPrimaryEvidence} />
      )}

      {evidence.length > 0 ? (
        hasStructuredPrimary && displayCount === 0 ? (
          <div className="flex items-start gap-3 rounded-xl border border-border bg-card px-4 py-3">
            <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
            <div>
              <p className="text-xs font-medium text-foreground">Only eligible deal in scope</p>
              <p className="mt-1 text-xs leading-5 text-muted-foreground">
                This is the only eligible structured deal for this query.
              </p>
            </div>
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-border bg-card">
            <button
              type="button"
              onClick={() => setShowAllEvidence((current) => !current)}
              aria-expanded={showAllEvidence}
              disabled={displayCount === 0}
              className="flex w-full flex-wrap items-center justify-between gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/30 disabled:cursor-default disabled:hover:bg-transparent"
            >
              <div>
                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                  {hasStructuredPrimary ? 'Supporting evidence' : 'Evidence'}
                </h2>
                <p className="mt-0.5 text-[11px] text-muted-foreground">
                  {`${displayCount} record${displayCount === 1 ? '' : 's'}`}
                  {crmEvidence.length > 0 && narrativeEvidence.length > 0
                    ? ` · ${crmEvidence.length} CRM · ${narrativeEvidence.length} other`
                    : ''}
                </p>
              </div>
              {displayCount > 0 && (
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md border border-border text-muted-foreground transition-transform duration-200">
                  {showAllEvidence ? (
                    <ChevronUp className="h-3.5 w-3.5" />
                  ) : (
                    <ChevronDown className="h-3.5 w-3.5" />
                  )}
                </span>
              )}
            </button>

            <div
              className={`grid transition-all duration-300 ease-in-out ${
                showAllEvidence ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'
              }`}
            >
              <div className="overflow-hidden">
                <div className="border-t border-border">
                  {crmEvidence.length > 0 ? (
                    <div>
                      <p className="px-4 pt-3 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        CRM deals
                      </p>
                      <EvidenceTable
                        evidence={crmEvidence}
                        selectedName={hasStructuredPrimary ? primaryNames[0] : null}
                      />
                    </div>
                  ) : null}
                  {narrativeEvidence.length > 0 ? (
                    <div className={crmEvidence.length > 0 ? 'border-t border-border' : ''}>
                      <p className="px-4 pt-3 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        Slack / Notion / Chat
                      </p>
                      <SupportingEvidenceList evidence={narrativeEvidence} />
                    </div>
                  ) : null}
                </div>
              </div>
            </div>
          </div>
        )
      ) : (
        <div className="rounded-xl border border-warning/30 bg-warning/5 px-4 py-3">
          <p className="text-xs font-medium text-warning">No structured Zoho evidence returned</p>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">
            The answer may rely on other indexed sources.
          </p>
        </div>
      )}
    </div>
  );
}

export function InvestigationPage() {
  const overview = useIndexOverview();
  const { refreshStatus, remaining, isTrialReady } = useTrialSession();
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [conversationBag, setConversationBag] = useState<Record<string, unknown> | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [rightPanelCollapsed, setRightPanelCollapsed] = useState(false);
  const [rightPanelWidth, setRightPanelWidth] = useState(380);
  const [isResizingPanel, setIsResizingPanel] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const panelResizeRef = useRef<{ startX: number; startWidth: number } | null>(null);

  const PANEL_MIN_WIDTH = 280;
  const PANEL_MAX_WIDTH = 640;
  const PANEL_COLLAPSED_WIDTH = 44;

  const startPanelResize = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (rightPanelCollapsed || isResizingPanel) {
      return;
    }
    panelResizeRef.current = { startX: event.clientX, startWidth: rightPanelWidth };
    setIsResizingPanel(true);
  };

  useEffect(() => {
    if (!isResizingPanel) {
      return;
    }
    const onMouseMove = (event: MouseEvent) => {
      const start = panelResizeRef.current;
      if (!start) {
        return;
      }
      const delta = start.startX - event.clientX;
      const nextWidth = Math.min(
        PANEL_MAX_WIDTH,
        Math.max(PANEL_MIN_WIDTH, start.startWidth + delta),
      );
      setRightPanelWidth(nextWidth);
    };
    const onMouseUp = () => {
      panelResizeRef.current = null;
      setIsResizingPanel(false);
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [isResizingPanel]);

  const isZohoIndexed = overview.raw?.sourceTypes.includes('ZOHO') ?? false;
  // `@` menu offers only the sources this workspace actually indexes.
  const crmSources = sourceOptions(overview.raw?.sourceTypes ?? []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const selectedMessage =
    messages.find((message) => message.id === selectedId) ?? messages[messages.length - 1];

  const runInvestigation = async (raw?: string) => {
    const scoped = parseScopedPrompt(raw ?? query);
    const trimmedQuery = COMMAND_PROMPTS.get(scoped.text) ?? scoped.text;
    if (!trimmedQuery || isLoading) {
      return;
    }

    const id = crypto.randomUUID();
    const startedAt = Date.now();
    setMessages((prev) => [
      ...prev,
      { id, query: trimmedQuery, status: 'loading', progressEvents: [], startedAt },
    ]);
    setSelectedId(id);
    setQuery('');
    setIsLoading(true);

    try {
      const response = await investigateCrmStream({
        query: trimmedQuery,
        sourceTypes: scoped.sourceTypes.length > 0 ? scoped.sourceTypes : undefined,
        conversationBag,
        onEvent: (event) => {
          if (event.type !== 'progress') {
            return;
          }
          setMessages((prev) =>
            prev.map((message) =>
              message.id === id
                ? {
                    ...message,
                    progressEvents: [...(message.progressEvents ?? []), event],
                  }
                : message,
            ),
          );
        },
      });
      setMessages((prev) =>
        prev.map((message) =>
          message.id === id
            ? { ...message, status: 'done', result: response, settledAt: Date.now() }
            : message,
        ),
      );
      const nextBag = response.metadata?.conversationBag;
      setConversationBag(
        nextBag && typeof nextBag === 'object' ? (nextBag as Record<string, unknown>) : null,
      );
      if (isTrialReady) {
        void refreshStatus();
      }
    } catch (caught) {
      const errorMessage =
        caught instanceof TrialLimitError
          ? `${caught.message} Switch to Local Pro for unlimited local queries.`
          : caught instanceof Error
            ? caught.message
            : 'Investigation failed';
      if (caught instanceof TrialLimitError) {
        void refreshStatus();
      }
      setMessages((prev) =>
        prev.map((message) =>
          message.id === id
            ? {
                ...message,
                status: 'error',
                error: errorMessage,
                settledAt: Date.now(),
              }
            : message,
        ),
      );
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex h-full min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center justify-end gap-3 px-5 py-2">
          <p className="mr-auto truncate text-[11px] text-muted-foreground">
            {overview.loading
              ? 'checking index'
              : isTrialReady && remaining != null
                ? `trial · ${remaining} queries left`
                : isZohoIndexed
                  ? 'zoho · index ready'
                  : 'zoho · not indexed'}
          </p>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="hidden h-8 w-8 p-0 lg:inline-flex"
            onClick={() => setRightPanelCollapsed((v) => !v)}
            aria-label={rightPanelCollapsed ? 'Expand evidence panel' : 'Minimize evidence panel'}
            title={rightPanelCollapsed ? 'Expand evidence panel' : 'Minimize evidence panel'}
          >
            {rightPanelCollapsed ? (
              <PanelRightOpen className="h-4 w-4 text-muted-foreground" />
            ) : (
              <PanelRightClose className="h-4 w-4 text-muted-foreground" />
            )}
          </Button>
        </div>

        <div ref={scrollRef} className="flex-1 space-y-4 overflow-y-auto px-5 py-5">
          {messages.length === 0 && (
            <div className="mx-auto max-w-lg pt-10 text-center">
              <p className="text-sm text-muted-foreground">
                Ask a question grounded in your indexed CRM data.
              </p>
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {QUICK_QUERIES.map((quickQuery) => (
                  <button
                    key={quickQuery.label}
                    type="button"
                    className="rounded-md border border-border bg-muted/40 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-foreground/20 hover:bg-muted hover:text-foreground"
                    onClick={() => void runInvestigation(quickQuery.query)}
                  >
                    {quickQuery.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((message) => (
            <div key={message.id} className="space-y-2">
              <div className="flex justify-end">
                <div className="max-w-[80%] rounded-2xl rounded-br-sm bg-muted px-4 py-2 text-sm text-foreground">
                  {message.query}
                </div>
              </div>

              {message.status === 'loading' ? (
                <div
                  className="inline-block w-auto max-w-2xl rounded-2xl rounded-bl-sm border border-primary/30 bg-card px-4 py-3 text-left shadow-sm"
                  onClick={() => setSelectedId(message.id)}
                  role="status"
                >
                  <LoadingAssistantBody message={message} />
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setSelectedId(message.id)}
                  className={`inline-block w-auto max-w-[85%] rounded-2xl rounded-bl-sm border px-4 py-3 text-left transition-colors ${
                    message.id === selectedMessage?.id
                      ? 'border-primary/40 bg-card'
                      : 'border-border bg-card hover:border-foreground/20'
                  }`}
                >
                  {(message.progressEvents?.length ?? 0) > 0 && (
                    <div className="mb-2">
                      <ThoughtTrail
                        rows={deriveProgressRows(message.progressEvents ?? [])}
                        startedAt={message.startedAt}
                        settledAt={message.settledAt}
                        status={message.status}
                      />
                    </div>
                  )}
                  {message.status === 'error' && (
                    <span className="flex items-center gap-2 text-xs text-destructive">
                      <AlertTriangle className="h-3.5 w-3.5" />
                      {message.error}
                    </span>
                  )}
                  {message.status === 'done' && message.result && (
                    <AnswerCard answer={message.result.answer} />
                  )}
                </button>
              )}
            </div>
          ))}
        </div>

        <div className="space-y-2 px-5 py-4">
          <PromptBar
            className="mx-auto"
            width={768}
            placeholder="Ask about your CRM data…"
            busy={isLoading}
            sources={crmSources}
            commands={CRM_COMMANDS}
            models={[]}
            efforts={[]}
            onSend={(text) => void runInvestigation(text)}
            background="var(--muted)"
            color="var(--foreground)"
            menuBackground="var(--card)"
            sparkColor="var(--primary)"
            radius={10}
            maxRows={6}
          />
          <div className="mx-auto flex max-w-3xl items-center gap-2">
            <ProfileMenu variant="composer" />
          </div>
        </div>
      </div>

      {rightPanelCollapsed ? (
        <aside
          style={{ width: PANEL_COLLAPSED_WIDTH }}
          className="hidden shrink-0 flex-col items-center border-l border-border py-3 lg:flex"
        >
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0"
            onClick={() => setRightPanelCollapsed(false)}
            aria-label="Expand evidence panel"
            title="Expand evidence panel"
          >
            <PanelRightOpen className="h-4 w-4 text-muted-foreground" />
          </Button>
        </aside>
      ) : (
        <aside
          style={{ width: rightPanelWidth }}
          className="relative hidden shrink-0 overflow-y-auto border-l border-border lg:block"
        >
          <div
            onMouseDown={startPanelResize}
            className={`absolute left-0 top-0 z-10 h-full w-1.5 -translate-x-0.5 cursor-col-resize select-none hover:bg-muted-foreground/20 ${
              isResizingPanel ? 'bg-muted-foreground/30' : ''
            }`}
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize evidence panel"
          />
          <RightPanel message={selectedMessage} />
        </aside>
      )}
    </div>
  );
}
