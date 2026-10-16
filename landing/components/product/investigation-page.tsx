"use client";

import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  AlertTriangle,
  ArrowUpRight,
  Check,
  ChevronDown,
  ChevronUp,
  Database,
  Info,
  LoaderCircle,
  Search,
  Sparkles,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogCloseButton,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useToast } from '@/components/ui/use-toast';
import { ConfigureModal } from '@/components/app-shell/configure-modal';
import { IntegrationIcon } from '@/components/app-shell/integration-icon';
import { TrialLimitPanel } from '@/components/app-shell/trial-limit-panel';
import { useAndroConfig } from '@/hooks/use-andro-config';
import { useIndexOverview } from '@/hooks/use-index-overview';
import { useTrial } from '@/hooks/use-trial';
import {
  getChatProviderSelection,
  investigateCrmStream,
  listSlackChannels,
  sendSlackMessage,
  TrialLimitError,
  updateChatProvider,
  type ChatProviderId,
  type ChatProviderSelection,
  type InvestigationAnswer,
  type InvestigationEvidence,
  type InvestigationProgressEvent,
  type InvestigationResponse,
  type SlackChannelOption,
} from '@/lib/api/andromedia';
import { docsQuestions } from '@/lib/docs-content';
import type { IntegrationId, SlackCredentials } from '@/types/product-config';
const QUICK_QUERIES = docsQuestions.good.map((query) => ({
  label: query,
  query,
}));

/** Non-exhaustive: sources without a brand logo (e.g. CODE) simply render without an icon. */
const SOURCE_TYPE_TO_INTEGRATION: Partial<Record<string, IntegrationId>> = {
  ZOHO: 'zoho',
  SLACK: 'slack',
  NOTION: 'notion',
  JIRA: 'jira',
  LINEAR: 'linear',
  GIT: 'git',
};

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

/**
 * Append-only activity transcript: every progress event becomes its own log line
 * (Cursor/Claude-style). No collapsing running→done into a single row.
 */
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
      <div className="absolute right-0 z-20 mt-2 w-64 max-w-[min(16rem,calc(100vw-2rem))] space-y-3 rounded-md border border-border bg-background p-3 shadow-md sm:max-w-[min(16rem,70vw)]">
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
  { heading: 'Source', width: '8%', align: 'left' as const },
  { heading: 'Record', width: '22%', align: 'left' as const },
  { heading: 'Probability', width: '11%', align: 'right' as const },
  { heading: 'Stage', width: '13%', align: 'left' as const },
  { heading: 'Amount', width: '11%', align: 'right' as const },
  { heading: 'Close date', width: '10%', align: 'right' as const },
  { heading: 'Modified', width: '10%', align: 'right' as const },
  { heading: 'Account', width: '9%', align: 'left' as const },
  { heading: 'Details', width: '6%', align: 'left' as const },
] as const;

function evidenceIntegrationId(source: string): IntegrationId | undefined {
  return SOURCE_TYPE_TO_INTEGRATION[source.toUpperCase()];
}

function isZohoEvidence(item: InvestigationEvidence): boolean {
  return item.source.toUpperCase() === 'ZOHO';
}

function EvidenceCards({
  evidence,
  selectedName,
}: {
  evidence: InvestigationEvidence[];
  selectedName?: string | null;
}) {
  const selectedKey = normalizeDealName(selectedName);

  return (
    <div className="space-y-3 p-3 md:hidden">
      {evidence.map((item, index) => {
        const isSelected = selectedKey.length > 0 && normalizeDealName(item.name) === selectedKey;
        const integrationId = evidenceIntegrationId(item.source);
        const zoho = isZohoEvidence(item);
        const fields = zoho
          ? [
              {
                label: 'Probability',
                value: formatStructuredValue('Probability', item.structured.Probability ?? '—'),
              },
              { label: 'Stage', value: item.structured.Stage ?? '—' },
              {
                label: 'Amount',
                value: formatStructuredValue('Amount', item.structured.Amount ?? '—'),
              },
              {
                label: 'Close date',
                value: formatStructuredValue(
                  'Close Date',
                  item.structured['Close Date'] ?? item.structured.Close ?? '—',
                ),
              },
              {
                label: 'Modified',
                value: formatStructuredValue('Modified', item.structured.Modified ?? '—'),
              },
              { label: 'Account', value: item.structured.Account ?? '—' },
            ]
          : [
              { label: 'Source', value: item.source },
              {
                label: 'Excerpt',
                value: item.narrative?.trim()
                  ? item.narrative.trim().slice(0, 160) + (item.narrative.length > 160 ? '…' : '')
                  : '—',
              },
            ];

        return (
          <article
            key={`${item.source}-${item.name}-${index}`}
            className={`rounded-xl border p-4 ${isSelected ? 'border-primary/40 bg-primary/5' : 'border-border bg-background/40'}`}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-1.5">
                  {integrationId ? (
                    <IntegrationIcon id={integrationId} size="sm" className="h-4 w-4 shrink-0" />
                  ) : null}
                  <h3 className="text-sm font-medium text-foreground">{item.name}</h3>
                </div>
                {isSelected ? (
                  <span className="mt-1 inline-block text-[10px] font-medium uppercase tracking-[0.12em] text-primary">
                    Top result
                  </span>
                ) : null}
              </div>
              <EvidenceDetails evidence={item} />
            </div>
            <dl className="mt-3 grid grid-cols-2 gap-3">
              {fields.map((field) => (
                <div key={field.label} className="min-w-0">
                  <dt className="text-[10px] uppercase tracking-[0.12em] text-muted-foreground">
                    {field.label}
                  </dt>
                  <dd className="mt-1 text-xs text-foreground [overflow-wrap:anywhere]">
                    {field.value}
                  </dd>
                </div>
              ))}
            </dl>
          </article>
        );
      })}
    </div>
  );
}

function EvidenceTable({
  evidence,
  selectedName,
}: {
  evidence: InvestigationEvidence[];
  selectedName?: string | null;
}) {
  const selectedKey = normalizeDealName(selectedName);

  return (
    <>
      <EvidenceCards evidence={evidence} selectedName={selectedName} />
      <div className="hidden overflow-x-auto px-2 py-1 md:block">
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
            const integrationId = evidenceIntegrationId(item.source);
            const zoho = isZohoEvidence(item);
            const cellTone = isSelected ? 'text-foreground' : 'text-muted-foreground';
            return (
              <tr
                key={`${item.source}-${item.name}-${index}`}
                className={`border-b border-border last:border-0 ${
                  isSelected ? 'bg-primary/5' : ''
                }`}
              >
                <td className="px-2 py-3 align-middle">
                  {integrationId ? (
                    <IntegrationIcon id={integrationId} size="sm" className="h-4 w-4" />
                  ) : (
                    <span className="text-[10px] uppercase tracking-[0.12em] text-muted-foreground">
                      {item.source}
                    </span>
                  )}
                </td>
                <th
                  scope="row"
                  title={item.name}
                  className={`truncate px-2 py-3 text-left text-xs font-medium align-middle ${cellTone}`}
                >
                  {item.name}
                  {isSelected && (
                    <span className="ml-2 text-[10px] font-normal uppercase tracking-[0.12em] text-primary">
                      Top
                    </span>
                  )}
                </th>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${cellTone}`}
                >
                  {zoho
                    ? formatStructuredValue('Probability', item.structured.Probability ?? '—')
                    : '—'}
                </td>
                <td
                  title={zoho ? item.structured.Stage ?? undefined : item.narrative?.slice(0, 120)}
                  className={`truncate px-2 py-3 text-left text-xs align-middle ${cellTone}`}
                >
                  {zoho
                    ? (item.structured.Stage ?? '—')
                    : item.narrative?.trim()
                      ? `${item.narrative.trim().slice(0, 48)}${item.narrative.length > 48 ? '…' : ''}`
                      : '—'}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${cellTone}`}
                >
                  {zoho ? formatStructuredValue('Amount', item.structured.Amount ?? '—') : '—'}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${cellTone}`}
                >
                  {zoho
                    ? formatStructuredValue(
                        'Close Date',
                        item.structured['Close Date'] ?? item.structured.Close ?? '—',
                      )
                    : '—'}
                </td>
                <td
                  className={`truncate px-2 py-3 text-right text-xs tabular-nums align-middle ${cellTone}`}
                >
                  {zoho
                    ? formatStructuredValue('Modified', item.structured.Modified ?? '—')
                    : '—'}
                </td>
                <td
                  title={item.structured.Account ?? undefined}
                  className={`truncate px-2 py-3 text-left text-xs align-middle ${cellTone}`}
                >
                  {zoho ? (item.structured.Account ?? '—') : '—'}
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
    </>
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
    <article className="rounded-xl border border-primary/40 bg-card px-4 py-4 sm:px-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[10px] font-medium uppercase tracking-[0.16em] text-primary">
            Top result
          </p>
          <h2 className="mt-1 text-base font-semibold text-foreground sm:truncate" title={displayName}>
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
  evidenceFlags,
  onSendToSlack,
}: {
  answer: InvestigationAnswer | null | undefined;
  evidenceFlags?: string[];
  onSendToSlack?: () => void;
}) {
  const reasons = answer?.reasons ?? [];

  return (
    <article className="rounded-xl border border-border bg-card px-4 py-4 sm:px-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <p className="min-w-0 flex-1 whitespace-pre-wrap text-base font-medium leading-7 text-foreground sm:text-lg sm:leading-8">
          {answer?.summaryText || 'No synthesized answer was returned.'}
        </p>
        {onSendToSlack ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="shrink-0"
            onClick={onSendToSlack}
          >
            Send to Slack
          </Button>
        ) : null}
      </div>

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

      {evidenceFlags && evidenceFlags.length > 0 ? (
        <p className="mt-3 text-[11px] text-muted-foreground">
          Flags: {evidenceFlags.join(', ')}
        </p>
      ) : null}
    </article>
  );
}

export function InvestigationPage() {
  const { serviceName, isTrial, trialId, getAccessToken, refreshStatus } = useTrial();
  const { integrations } = useAndroConfig();
  const { toast } = useToast();
  const overview = useIndexOverview(
    serviceName,
    isTrial && trialId ? trialId : undefined,
    isTrial ? getAccessToken : undefined,
  );
  const [query, setQuery] = useState<string>(QUICK_QUERIES[0].query);
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [result, setResult] = useState<InvestigationResponse | null>(null);
  const [conversationBag, setConversationBag] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [trialLimitHit, setTrialLimitHit] = useState(false);
  const [trialLimitDetails, setTrialLimitDetails] = useState<{ used: number; max: number } | null>(
    null,
  );
  const [isLoading, setIsLoading] = useState(false);
  const [progressEvents, setProgressEvents] = useState<InvestigationProgressEvent[]>([]);
  const progressLogRef = useRef<HTMLDivElement | null>(null);
  const [showAllEvidence, setShowAllEvidence] = useState(false);
  const [chatProviderSelection, setChatProviderSelection] =
    useState<ChatProviderSelection | null>(null);
  const [isProviderLoading, setIsProviderLoading] = useState(true);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [promptRows, setPromptRows] = useState(3);
  const [configureId, setConfigureId] = useState<IntegrationId | null>(null);
  const [slackDialogOpen, setSlackDialogOpen] = useState(false);
  const [slackChannels, setSlackChannels] = useState<SlackChannelOption[]>([]);
  const [slackChannelId, setSlackChannelId] = useState('');
  const [slackChannelsLoading, setSlackChannelsLoading] = useState(false);
  const [slackSending, setSlackSending] = useState(false);

  const indexedSourceTypes = overview.raw?.sourceTypes ?? [];
  const isZohoIndexed = indexedSourceTypes.includes('ZOHO');
  const hasAnyIndex = indexedSourceTypes.length > 0;
  const syncSuffix =
    overview.loading || !overview.raw
      ? null
      : overview.freshness === 'stale'
        ? ` · last sync ${overview.lastSync} · stale`
        : overview.freshness === 'aging'
          ? ` · last sync ${overview.lastSync} · aging`
          : overview.lastSync !== 'never'
            ? ` · last sync ${overview.lastSync}`
            : null;
  const sourceStatus = overview.loading
    ? 'checking index'
    : hasAnyIndex
      ? `${indexedSourceTypes.map((t) => t.toLowerCase()).join(' · ')} · ${
          isTrial ? 'trial index ready' : 'index ready'
        }${syncSuffix ?? ''}`
      : 'no sources indexed yet';
  const showStaleWarning = isZohoIndexed && overview.freshness === 'stale';

  useEffect(() => {
    const mediaQuery = window.matchMedia('(max-width: 639px)');
    const updatePromptRows = () => setPromptRows(mediaQuery.matches ? 4 : 1);
    updatePromptRows();
    mediaQuery.addEventListener('change', updatePromptRows);
    return () => mediaQuery.removeEventListener('change', updatePromptRows);
  }, []);

  useEffect(() => {
    if (isTrial) {
      setIsProviderLoading(false);
      return;
    }
    let isMounted = true;
    getChatProviderSelection({
      trialId: isTrial ? trialId : serviceName || "local",
    })
      .then((selection) => {
        if (isMounted) {
          setChatProviderSelection(selection);
          setProviderError(null);
        }
      })
      .catch((caught) => {
        if (isMounted) {
          setProviderError(caught instanceof Error ? caught.message : 'Provider lookup failed');
        }
      })
      .finally(() => {
        if (isMounted) {
          setIsProviderLoading(false);
        }
      });
    return () => {
      isMounted = false;
    };
  }, [isTrial, serviceName, trialId]);

  const progressRows = useMemo(() => deriveProgressRows(progressEvents), [progressEvents]);

  useEffect(() => {
    const el = progressLogRef.current;
    if (!el || progressRows.length === 0) {
      return;
    }
    el.scrollTop = el.scrollHeight;
  }, [progressRows]);

  const evidence = useMemo(() => result?.evidence ?? [], [result]);
  const sourcesTouched = useMemo(() => {
    const types = new Set(
      evidence.map((item) => item.source?.toUpperCase()).filter((value): value is string => Boolean(value)),
    );
    return Array.from(types);
  }, [evidence]);
  const referenceCount = result?.metadata.referenceCount ?? evidence.length;

  // A conflict-adjudicated answer is grounded in a real record too — it just also had to
  // reconcile a disagreement between sources, so it must render with the same primary card.
  const hasStructuredPrimary =
    (result?.answer?.trustState === 'structured' ||
      result?.answer?.trustState === 'conflict_adjudicated') &&
    Boolean(result.answer.recordName?.trim()) &&
    Object.keys(result.answer.primaryFields ?? {}).length > 0;

  const primaryNames = useMemo(
    () => primaryDealNames(result?.answer?.recordName),
    [result?.answer?.recordName],
  );

  const primaryNameKey = normalizeDealName(primaryNames[0]);
  const primaryAccountKey = normalizeDealName(
    result?.answer?.primaryFields?.Account ?? result?.answer?.primaryFields?.account,
  );

  const matchedPrimaryEvidence = useMemo(() => {
    if (!primaryNameKey) {
      return null;
    }
    const nameMatches = evidence.filter(
      (item) => normalizeDealName(item.name) === primaryNameKey,
    );
    if (nameMatches.length === 0) {
      return null;
    }
    if (primaryAccountKey) {
      const accountMatch = nameMatches.find(
        (item) =>
          normalizeDealName(item.structured.Account ?? item.structured.account) ===
          primaryAccountKey,
      );
      if (accountMatch) {
        return accountMatch;
      }
    }
    return nameMatches[0] ?? null;
  }, [evidence, primaryAccountKey, primaryNameKey]);

  const supportingEvidence = useMemo(() => {
    if (!hasStructuredPrimary || !matchedPrimaryEvidence) {
      return evidence;
    }
    // Exclude only the selected Top result row (singular), not every same-named deal.
    return evidence.filter((item) => item !== matchedPrimaryEvidence);
  }, [evidence, hasStructuredPrimary, matchedPrimaryEvidence]);

  const selectChatProvider = async (provider: ChatProviderId) => {
    if (isProviderLoading || provider === chatProviderSelection?.activeProvider) {
      return;
    }
    setIsProviderLoading(true);
    setProviderError(null);
    try {
      setChatProviderSelection(
        await updateChatProvider(provider, {
          trialId: isTrial ? trialId : serviceName || "local",
        }),
      );
    } catch (caught) {
      setProviderError(caught instanceof Error ? caught.message : 'Provider update failed');
    } finally {
      setIsProviderLoading(false);
    }
  };

  const runInvestigation = async (event?: FormEvent) => {
    event?.preventDefault();
    const trimmedQuery = query.trim();
    if (!trimmedQuery || isLoading) {
      return;
    }

    setSubmittedQuery(trimmedQuery);
    setResult(null);
    setError(null);
    setTrialLimitHit(false);
    setTrialLimitDetails(null);
    setShowAllEvidence(false);
    setIsLoading(true);
    setProgressEvents([]);

    try {
      const token = isTrial ? await getAccessToken() : null;
      const response = await investigateCrmStream({
        query: trimmedQuery,
        service: serviceName,
        trialId: isTrial ? trialId ?? undefined : undefined,
        accessToken: token,
        conversationBag,
        onEvent: (event) => setProgressEvents((current) => [...current, event]),
      });
      setResult(response);
      const nextBag = response.metadata?.conversationBag;
      setConversationBag(
        nextBag && typeof nextBag === 'object' ? (nextBag as Record<string, unknown>) : null,
      );
      void refreshStatus();
    } catch (caught) {
      if (caught instanceof TrialLimitError) {
        setTrialLimitHit(true);
        setTrialLimitDetails({ used: caught.queriesUsed, max: caught.maxQueries });
        void refreshStatus();
      } else {
        setError(caught instanceof Error ? caught.message : 'Investigation failed');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const slackBotToken = useMemo(() => {
    const slack = integrations.find((item) => item.id === 'slack');
    return (slack?.credentials as SlackCredentials | undefined)?.botToken?.trim() ?? '';
  }, [integrations]);

  const primaryEvidenceFlags = matchedPrimaryEvidence?.flags ?? [];

  const openSendToSlack = async () => {
    if (!result?.answer) {
      return;
    }
    if (!slackBotToken) {
      toast({
        title: 'Slack not configured',
        description: 'Open Sources and configure a Slack bot token with chat:write, then try again.',
      });
      setConfigureId('slack');
      return;
    }
    setSlackDialogOpen(true);
    setSlackChannelsLoading(true);
    setSlackChannelId('');
    try {
      const channels = await listSlackChannels({ botToken: slackBotToken });
      setSlackChannels(channels);
      const preferred = channels.find((channel) => channel.isMember) ?? channels[0];
      if (preferred) {
        setSlackChannelId(preferred.id);
      }
    } catch (caught) {
      toast({
        title: 'Unable to list Slack channels',
        description: caught instanceof Error ? caught.message : 'Unknown error',
      });
      setSlackDialogOpen(false);
    } finally {
      setSlackChannelsLoading(false);
    }
  };

  const confirmSendToSlack = async () => {
    if (!result?.answer || !slackChannelId || !slackBotToken) {
      return;
    }
    setSlackSending(true);
    try {
      const token = isTrial ? await getAccessToken() : null;
      await sendSlackMessage({
        service: serviceName,
        trialId: isTrial ? trialId ?? undefined : undefined,
        accessToken: token,
        botToken: slackBotToken,
        channelId: slackChannelId,
        title: result.answer.recordName || result.answer.title || 'Investigate',
        summary: result.answer.summaryText || result.summary,
        reasons: result.answer.reasons,
        primaryFields: result.answer.primaryFields,
        flags: primaryEvidenceFlags,
        link:
          typeof window !== 'undefined'
            ? `${window.location.origin}/app/investigate`
            : '/app/investigate',
      });
      toast({
        title: 'Sent to Slack',
        description: 'Investigate answer posted with evidence and flags.',
      });
      setSlackDialogOpen(false);
    } catch (caught) {
      toast({
        title: 'Send to Slack failed',
        description: caught instanceof Error ? caught.message : 'Unknown error',
      });
    } finally {
      setSlackSending(false);
    }
  };

  return (
    <>
    <div className="space-y-4 sm:space-y-5">
      <section className="overflow-hidden rounded-xl border border-border bg-card">
        <div className="flex flex-col gap-4 border-b border-border px-4 py-4 sm:flex-row sm:flex-wrap sm:items-center sm:gap-3 sm:px-5 sm:py-5">
          <div className="flex min-w-0 items-start gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-primary/30 bg-primary/10">
              <Search className="h-4 w-4 text-primary" />
            </div>
            <div className="min-w-0">
              <h1 className="text-sm font-medium text-foreground">
                andro<span className="text-primary">.</span>investigate
              </h1>
              <p className="mt-0.5 text-xs leading-5 text-muted-foreground">
                Concise CRM answers with aligned top result and supporting evidence
              </p>
            </div>
          </div>
          <div className="flex w-full flex-col gap-3 text-[11px] text-muted-foreground sm:ml-auto sm:w-auto sm:flex-row sm:flex-wrap sm:items-center sm:justify-end">
            {!isTrial && (
              <div className="flex w-full items-center gap-2 pb-1 sm:w-auto sm:pb-0">
                <label className="sr-only" htmlFor="chat-provider">
                  Chat provider
                </label>
                <span aria-hidden="true">LLM</span>
                <select
                  id="chat-provider"
                  value={chatProviderSelection?.activeProvider ?? 'groq'}
                  onChange={(event) => void selectChatProvider(event.target.value as ChatProviderId)}
                  disabled={isProviderLoading || !chatProviderSelection}
                  title={providerError ?? 'Select the backend chat provider'}
                  className="h-8 w-full min-w-0 rounded-md border border-input bg-background px-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto"
                >
                  {(chatProviderSelection?.providers ?? [
                    {
                      id: 'groq',
                      label: 'Groq',
                      configured: true,
                      apiKeyConfigured: true,
                      model: '',
                      active: true,
                    },
                    {
                      id: 'deepseek',
                      label: 'DeepSeek',
                      configured: false,
                      apiKeyConfigured: false,
                      model: '',
                      active: false,
                    },
                    {
                      id: 'openrouter',
                      label: 'OpenRouter',
                      configured: false,
                      apiKeyConfigured: false,
                      model: '',
                      active: false,
                    },
                    {
                      id: 'ollama',
                      label: 'Ollama (local)',
                      configured: false,
                      apiKeyConfigured: true,
                      model: '',
                      active: false,
                    },
                  ]).map((provider) => (
                    <option
                      key={provider.id}
                      value={provider.id}
                      disabled={!provider.configured}
                    >
                      {`${provider.label}${provider.configured ? '' : ' (not configured)'}`}
                    </option>
                  ))}
                </select>
              </div>
            )}
            {isTrial && (
              <span className="rounded-full border border-primary/30 bg-primary/10 px-2.5 py-1 text-primary">
                LLM included
              </span>
            )}
            {!isTrial && providerError && (
              <span className="max-w-56 truncate text-destructive" title={providerError}>
                {providerError}
              </span>
            )}
            <div className="flex flex-wrap items-center gap-2">
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  hasAnyIndex
                    ? overview.freshness === 'stale'
                      ? 'bg-warning'
                      : 'bg-primary'
                    : 'bg-warning'
                }`}
              />
              <span className={showStaleWarning ? 'text-warning' : undefined}>{sourceStatus}</span>
              {isZohoIndexed ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 px-2.5 text-[11px]"
                  onClick={() => setConfigureId('zoho')}
                >
                  Reindex Zoho
                </Button>
              ) : null}
            </div>
          </div>
        </div>

        {showStaleWarning ? (
          <div className="mx-4 mt-3 flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-xs text-warning sm:mx-5">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <p>
              CRM index is stale (last sync {overview.lastSync}). Reindex with a full refresh to
              replace the Zoho snapshot before trusting follow-up answers.
            </p>
          </div>
        ) : null}

        <div className="space-y-6 px-4 py-6 sm:space-y-4 sm:px-5 sm:py-5">
          <form className="flex flex-col gap-5 sm:flex-row sm:items-stretch sm:gap-2" onSubmit={runInvestigation}>
            <div className="flex min-w-0 flex-1 flex-col gap-2.5">
              <label
                htmlFor="crm-investigation-query"
                className="text-xs font-medium uppercase tracking-[0.14em] text-muted-foreground sm:sr-only"
              >
                Your question
              </label>
              <textarea
                id="crm-investigation-query"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="What is the most qualified active deal?"
                rows={promptRows}
                disabled={isLoading}
                className="block min-h-[9.5rem] w-full resize-none rounded-xl border border-input bg-background px-4 py-4 text-base leading-7 text-foreground outline-none transition-colors placeholder:text-muted-foreground focus:border-primary focus:ring-2 focus:ring-primary/20 disabled:cursor-not-allowed disabled:opacity-60 max-sm:[field-sizing:fixed] sm:min-h-10 sm:rounded-lg sm:px-3 sm:py-2.5 sm:text-sm sm:leading-5"
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    void runInvestigation();
                  }
                }}
              />
            </div>
            <Button
              type="submit"
              size="lg"
              className="min-h-12 w-full shrink-0 px-5 text-base sm:min-h-10 sm:w-auto sm:self-end sm:text-sm"
              disabled={isLoading || !query.trim()}
            >
              {isLoading ? (
                <>
                  <LoaderCircle className="h-4 w-4 animate-spin" />
                  Investigating
                </>
              ) : (
                <>
                  Investigate
                  <ArrowUpRight className="h-4 w-4" />
                </>
              )}
            </Button>
          </form>

          <div className="space-y-3 border-t border-border/60 pt-5">
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Sparkles className="size-3.5 shrink-0" />
              <span>Try asking:</span>
            </div>
            <div className="grid grid-cols-1 gap-2 min-[420px]:grid-cols-2 sm:flex sm:flex-wrap">
              {QUICK_QUERIES.map((quickQuery) => (
                <button
                  key={quickQuery.label}
                  type="button"
                  className="w-full rounded-xl border border-border bg-muted/40 px-3.5 py-2.5 text-left text-sm leading-5 text-muted-foreground transition-colors hover:border-foreground/20 hover:bg-muted hover:text-foreground disabled:opacity-50 sm:w-auto sm:rounded-lg sm:py-1.5 sm:text-xs"
                  onClick={() => setQuery(quickQuery.query)}
                  disabled={isLoading}
                >
                  {quickQuery.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>

      {isLoading && (
        <section
          className="rounded-xl border border-border bg-card px-5 py-4"
          aria-live="polite"
          aria-busy={true}
        >
          <div className="mb-3 flex items-center justify-between gap-3">
            <h2 className="text-[10px] font-medium uppercase tracking-[0.18em] text-muted-foreground">
              Working…
            </h2>
            <span className="text-[10px] text-muted-foreground">
              Live activity log — {progressRows.length || 0} event
              {progressRows.length === 1 ? '' : 's'}
            </span>
          </div>
          {progressRows.length === 0 ? (
            <div className="flex min-h-[7.5rem] items-start gap-2.5 py-2.5 text-xs text-muted-foreground">
              <LoaderCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" />
              Connecting…
            </div>
          ) : (
            <div
              ref={progressLogRef}
              className="min-h-[7.5rem] max-h-[15rem] space-y-1 overflow-y-auto font-mono text-[11px] leading-5"
            >
              {progressRows.map((row, index) => {
                const isLatest = index === progressRows.length - 1;
                const isRunning = row.status === 'running';
                const isConflict = row.step === 'conflicts';
                const integrationId = row.sourceType
                  ? SOURCE_TYPE_TO_INTEGRATION[row.sourceType]
                  : undefined;
                return (
                  <div
                    key={row.key}
                    className={`flex items-start gap-2 rounded px-1 py-0.5 ${
                      isLatest ? 'bg-muted/40 text-foreground' : 'text-muted-foreground'
                    }`}
                  >
                    <span className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center">
                      {isConflict ? (
                        <Sparkles className="h-3 w-3 text-primary" />
                      ) : isLatest && isRunning ? (
                        <LoaderCircle className="h-3 w-3 animate-spin" />
                      ) : row.status === 'done' ? (
                        <Check className="h-3 w-3 text-primary/70" />
                      ) : (
                        <span className="text-[10px] text-muted-foreground">·</span>
                      )}
                    </span>
                    {integrationId ? (
                      <IntegrationIcon id={integrationId} size="sm" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    ) : null}
                    <p
                      className={`min-w-0 flex-1 [overflow-wrap:anywhere] ${
                        isConflict ? 'font-medium text-primary' : ''
                      }`}
                    >
                      {row.detail ?? stepFallbackLabel(row.step)}
                    </p>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}

      {trialLimitHit && trialLimitDetails && (
        <TrialLimitPanel
          queriesUsed={trialLimitDetails.used}
          maxQueries={trialLimitDetails.max}
        />
      )}

      {error && (
        <section className="flex items-start gap-3 rounded-xl border border-destructive/40 bg-destructive/5 px-5 py-4">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
          <div>
            <h2 className="text-xs font-medium text-destructive">Investigation failed</h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">{error}</p>
          </div>
        </section>
      )}

      {result && !isLoading && (
        <section className="space-y-4">
          <div className="flex flex-col gap-2 rounded-lg border border-border bg-card px-4 py-3 text-[11px] text-muted-foreground sm:flex-row sm:flex-wrap sm:items-center">
            <div className="flex min-w-0 items-center gap-2">
              <Database className="h-3.5 w-3.5 shrink-0 text-muted-foreground/70" />
              <p>
                {`Grounded against ${referenceCount} indexed reference${referenceCount === 1 ? '' : 's'}`}
              </p>
            </div>
            <span className="hidden sm:inline" aria-hidden="true">
              ·
            </span>
            <span className="min-w-0 font-normal sm:truncate">{submittedQuery}</span>
          </div>

          <AnswerCard
            answer={result.answer}
            evidenceFlags={primaryEvidenceFlags}
            onSendToSlack={() => {
              void openSendToSlack();
            }}
          />

          {hasStructuredPrimary && result.answer && (
            <TopResultPanel answer={result.answer} matchedEvidence={matchedPrimaryEvidence} />
          )}

          {evidence.length > 0 ? (
            (() => {
              const displayEvidence = hasStructuredPrimary ? supportingEvidence : evidence;
              const displayCount = displayEvidence.length;

              if (hasStructuredPrimary && displayCount === 0) {
                return (
                  <div className="flex items-start gap-3 rounded-xl border border-border bg-card px-5 py-4">
                    <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                    <div>
                      <p className="text-xs font-medium text-foreground">
                        Only eligible deal in scope
                      </p>
                      <p className="mt-1 text-xs leading-5 text-muted-foreground">
                        This is the only eligible structured deal for this query, so there are no
                        additional records to compare.
                      </p>
                    </div>
                  </div>
                );
              }

              return (
                <div className="rounded-xl border border-border bg-card">
                  <div className="flex flex-col gap-3 px-4 py-4 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:px-5">
                    <div>
                      <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                        {hasStructuredPrimary ? 'Supporting evidence' : 'Evidence'}
                      </h2>
                      <p className="mt-0.5 text-[11px] text-muted-foreground">
                        {`${displayCount} record${displayCount === 1 ? '' : 's'}`}
                        {sourcesTouched.length > 0
                          ? ` · sources: ${sourcesTouched.join(', ')}`
                          : ''}
                      </p>
                    </div>
                    {displayCount > 0 && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="w-full sm:w-auto"
                        onClick={() => setShowAllEvidence((current) => !current)}
                        aria-expanded={showAllEvidence}
                      >
                        {showAllEvidence ? (
                          <>
                            Hide evidence
                            <ChevronUp className="h-3.5 w-3.5" />
                          </>
                        ) : (
                          <>
                            {`Show all evidence (${displayCount})`}
                            <ChevronDown className="h-3.5 w-3.5" />
                          </>
                        )}
                      </Button>
                    )}
                  </div>

                  {showAllEvidence && (
                    <div className="border-t border-border">
                      <EvidenceTable
                        evidence={displayEvidence}
                        selectedName={hasStructuredPrimary ? primaryNames[0] : null}
                      />
                    </div>
                  )}
                </div>
              );
            })()
          ) : (
            <div className="rounded-xl border border-warning/30 bg-warning/5 px-5 py-4">
              <p className="text-xs font-medium text-warning">No structured Zoho evidence returned</p>
              <p className="mt-1 text-xs leading-5 text-muted-foreground">
                The answer may rely on other indexed sources. Check that Zoho has been re-indexed
                with the latest structured metadata.
              </p>
            </div>
          )}
        </section>
      )}
    </div>
    <ConfigureModal
      integrationId={configureId}
      onClose={() => {
        setConfigureId(null);
        void overview.refresh();
      }}
    />
    <Dialog open={slackDialogOpen} onOpenChange={setSlackDialogOpen}>
      <DialogContent className="max-w-md p-4 sm:p-6">
        <DialogCloseButton />
        <DialogHeader>
          <DialogTitle>Send to Slack</DialogTitle>
        </DialogHeader>
        <div className="mt-4 space-y-4">
          <p className="text-xs text-muted-foreground">
            Posts the grounded answer with reasons, key fields, and flags. Bot needs{' '}
            <code className="text-[11px]">chat:write</code> and must be invited to the channel.
          </p>
          <label className="block space-y-1.5 text-xs text-muted-foreground">
            <span className="uppercase tracking-[0.12em]">Channel</span>
            <select
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm text-foreground shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              value={slackChannelId}
              disabled={slackChannelsLoading || slackSending}
              onChange={(event) => setSlackChannelId(event.target.value)}
            >
              {slackChannelsLoading ? (
                <option value="">Loading channels…</option>
              ) : slackChannels.length === 0 ? (
                <option value="">No channels found</option>
              ) : (
                slackChannels.map((channel) => (
                  <option key={channel.id} value={channel.id}>
                    #{channel.name}
                    {channel.isMember ? '' : ' (invite bot)'}
                  </option>
                ))
              )}
            </select>
          </label>
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={slackSending}
              onClick={() => setSlackDialogOpen(false)}
            >
              Cancel
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={!slackChannelId || slackChannelsLoading || slackSending}
              onClick={() => {
                void confirmSendToSlack();
              }}
            >
              {slackSending ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
    </>
  );
}
