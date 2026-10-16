import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useAndroConfig, buildClaudeSnippet } from '@/hooks/useAndroConfig';
import { useIndexOverview } from '@/hooks/useIndexOverview';
import { CopySnippet } from '@/components/CopySnippet';
import { ConnectorsSection } from '@/components/ConnectorsSection';
import { SourceFilterCheckboxes } from '@/components/SourceFilterCheckboxes';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog,
  DialogCloseButton,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  getChatProviderSelection,
  updateChatProvider,
  type ChatProviderId,
  type ChatProviderSelection,
} from '@/lib/andromediaApi';
import { useAppMode } from '@/hooks/useAppMode';
import { useTrialSession } from '@/hooks/useTrialSession';
import { cn } from '@/lib/utils';
import {
  buildSourceFilterOptions,
  isSupportedIntegration,
  pruneSourceFilters,
} from '@/types/config';

interface SettingsPanelProps {
  open: boolean;
  onClose: () => void;
}

const CLOUD_MODEL_PRESETS: Record<'groq' | 'deepseek' | 'openrouter', string[]> = {
  groq: ['qwen/qwen3.8-27b', 'llama-3.1-8b-instant', 'llama-3.1-70b-versatile'],
  deepseek: ['deepseek-v4-flash'],
  openrouter: [
    'meta-llama/llama-3.3-70b-instruct',
    'openai/gpt-4o-mini',
    'anthropic/claude-3.5-sonnet',
  ],
};

export function SettingsPanel({ open, onClose }: SettingsPanelProps) {
  const { mcp, setMcp, setMcpSourceFilter, save, integrations } = useAndroConfig();
  const overview = useIndexOverview();
  const { mode } = useAppMode();
  const { serviceName } = useTrialSession();
  const trialLocksLlm = mode === 'trial';

  const [chatSelection, setChatSelection] = useState<ChatProviderSelection | null>(null);
  const [provider, setProvider] = useState<ChatProviderId>('groq');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [chatError, setChatError] = useState<string | null>(null);
  const [chatStatus, setChatStatus] = useState<string | null>(null);
  const [isChatLoading, setIsChatLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  const snippet = useMemo(() => buildClaudeSnippet(mcp), [mcp]);
  const activeProviderStatus = chatSelection?.providers.find((item) => item.id === provider);

  const sourceOptions = useMemo(
    () =>
      buildSourceFilterOptions({
        indexedSourceTypes: overview.raw?.sourceTypes,
        enabledIntegrationIds: integrations
          .filter((item) => isSupportedIntegration(item.id) && item.enabled)
          .map((item) => item.id),
      }),
    [overview.raw?.sourceTypes, integrations],
  );

  const modelOptions = useMemo(() => {
    if (provider === 'ollama') {
      const discovered = activeProviderStatus?.availableModels ?? [];
      if (model && !discovered.includes(model)) {
        return [model, ...discovered];
      }
      return discovered;
    }
    const presets = CLOUD_MODEL_PRESETS[provider] ?? [];
    if (model && !presets.includes(model)) {
      return [model, ...presets];
    }
    return presets;
  }, [provider, model, activeProviderStatus?.availableModels]);

  const needsApiKey =
    provider !== 'ollama' &&
    !activeProviderStatus?.apiKeyConfigured &&
    apiKey.trim().length === 0;

  const ollamaUnavailable =
    provider === 'ollama' && (activeProviderStatus?.availableModels?.length ?? 0) === 0;

  const canSaveLlm =
    !needsApiKey &&
    !ollamaUnavailable &&
    model.trim().length > 0;

  const applySelection = useCallback(
    (selection: ChatProviderSelection, nextProvider?: ChatProviderId) => {
      setChatSelection(selection);
      const resolvedProvider = nextProvider ?? selection.activeProvider;
      setProvider(resolvedProvider);
      const active =
        selection.providers.find((item) => item.id === resolvedProvider) ??
        selection.providers.find((item) => item.id === selection.activeProvider);
      const discovered = active?.availableModels ?? [];
      if (resolvedProvider === 'ollama') {
        setModel(
          active?.model && discovered.includes(active.model)
            ? active.model
            : (discovered[0] ?? ''),
        );
      } else {
        const presets =
          CLOUD_MODEL_PRESETS[resolvedProvider as 'groq' | 'deepseek' | 'openrouter'] ?? [];
        setModel(active?.model || presets[0] || '');
      }
      setApiKey('');
    },
    [],
  );

  const loadChatSelection = useCallback(async () => {
    setIsChatLoading(true);
    setChatError(null);
    try {
      const selection = await getChatProviderSelection({ trialId: serviceName });
      applySelection(selection);
      setChatStatus(null);
    } catch (caught) {
      setChatError(caught instanceof Error ? caught.message : 'Failed to load LLM settings');
    } finally {
      setIsChatLoading(false);
    }
  }, [applySelection, serviceName]);

  useEffect(() => {
    if (!open) {
      return;
    }
    void loadChatSelection();
  }, [open, loadChatSelection]);

  const handleProviderChange = (nextProvider: ChatProviderId) => {
    if (!chatSelection) {
      setProvider(nextProvider);
      return;
    }
    applySelection(chatSelection, nextProvider);
    setChatStatus(null);
    setChatError(null);
  };

  const handleSave = async () => {
    setIsSaving(true);
    setChatError(null);
    setChatStatus(null);
    try {
      const nextMcp = {
        ...mcp,
        defaultSourceFilters: pruneSourceFilters(mcp.defaultSourceFilters, sourceOptions),
      };
      setMcp({ defaultSourceFilters: nextMcp.defaultSourceFilters });

      if (!trialLocksLlm) {
        if (!canSaveLlm) {
          throw new Error(
            ollamaUnavailable
              ? 'Ollama has no installed models. Run `ollama serve` and `ollama pull <model>`.'
              : needsApiKey
                ? 'Paste an API key for this provider, or choose a configured provider.'
                : 'Select a model before saving.',
          );
        }
        const selection = await updateChatProvider(
          {
            provider,
            model: model.trim() || undefined,
            apiKey: apiKey.trim() || undefined,
          },
          { trialId: serviceName },
        );
        applySelection(selection, selection.activeProvider);
        setChatStatus(`${selection.activeProvider} saved`);
      }

      save(undefined, nextMcp);
      onClose();
    } catch (caught) {
      setChatError(caught instanceof Error ? caught.message : 'Failed to save LLM settings');
    } finally {
      setIsSaving(false);
    }
  };

  const providerStatusLine = useMemo(() => {
    if (!activeProviderStatus) {
      return null;
    }
    if (provider === 'ollama') {
      const count = activeProviderStatus.availableModels?.length ?? 0;
      return count > 0
        ? `${count} local model(s) detected · current ${activeProviderStatus.model || model || '—'}`
        : 'Ollama unreachable or no models installed';
    }
    return `${activeProviderStatus.configured ? 'Configured' : 'Not configured'} · ${activeProviderStatus.model || model || '—'}`;
  }, [activeProviderStatus, provider, model]);

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? undefined : onClose())}>
      <DialogContent
        className="flex max-h-[85vh] w-[min(92vw,44rem)] max-w-3xl flex-col gap-0 overflow-hidden p-0"
        aria-describedby={undefined}
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border px-5 py-4">
          <DialogTitle className="text-sm text-foreground">Settings</DialogTitle>
          <DialogCloseButton />
        </div>

        <div className="min-h-0 flex-1 space-y-8 overflow-y-auto p-5">
          <section className="space-y-4">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-xs uppercase tracking-wider text-muted-foreground">LLM chat</h3>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-7 gap-1 px-2 text-[11px]"
                onClick={() => void loadChatSelection()}
                disabled={isChatLoading || isSaving}
              >
                <RefreshCw className={cn('h-3 w-3', isChatLoading && 'animate-spin')} />
                Refresh
              </Button>
            </div>
            <p className="text-xs text-muted-foreground leading-relaxed">
              {trialLocksLlm
                ? 'Trial mode uses the hosted chat model. Switch to Local Pro to change providers and API keys.'
                : 'Choose Groq, DeepSeek, OpenRouter, or local Ollama for investigation answers. Groq uses DeepSeek as a transient-failure fallback when both providers are configured. Cloud API keys stay in backend process memory only. Ollama lists models installed on this machine.'}
            </p>

            <div className="space-y-2">
              <Label className="text-xs">Provider</Label>
              <Select
                value={provider}
                onValueChange={(value) => handleProviderChange(value as ChatProviderId)}
                disabled={trialLocksLlm || isChatLoading || isSaving || !chatSelection}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(chatSelection?.providers ?? []).map((item) => (
                    <SelectItem key={item.id} value={item.id}>
                      {`${item.label}${item.configured ? '' : ' (not configured)'}`}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {providerStatusLine ? (
                <p className="text-[11px] text-muted-foreground">{providerStatusLine}</p>
              ) : null}
            </div>

            <div className="space-y-2">
              <Label className="text-xs">Model</Label>
              <Select
                value={model || undefined}
                onValueChange={setModel}
                disabled={trialLocksLlm || isChatLoading || isSaving || modelOptions.length === 0}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Select a model" />
                </SelectTrigger>
                <SelectContent>
                  {modelOptions.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {provider !== 'ollama' && (
                <Input
                  value={model}
                  onChange={(event) => setModel(event.target.value)}
                  placeholder="Or enter a custom model id"
                  disabled={trialLocksLlm || isChatLoading || isSaving}
                />
              )}
              {provider === 'ollama' && ollamaUnavailable ? (
                <p className="text-[11px] text-destructive">
                  No local Ollama models detected. Run `ollama serve` and `ollama pull &lt;model&gt;`.
                </p>
              ) : null}
            </div>

            {provider !== 'ollama' && (
              <div className="space-y-2">
                <Label className="text-xs">API key</Label>
                <Input
                  type="password"
                  value={apiKey}
                  onChange={(event) => setApiKey(event.target.value)}
                  placeholder={
                    activeProviderStatus?.apiKeyConfigured
                      ? '•••• configured — leave blank to keep'
                      : 'Paste provider API key'
                  }
                  autoComplete="off"
                  disabled={trialLocksLlm || isChatLoading || isSaving}
                />
                <p className="text-[11px] text-muted-foreground">
                  {activeProviderStatus?.apiKeyConfigured
                    ? 'A key is already configured for this provider.'
                    : 'No key configured yet for this provider.'}
                </p>
              </div>
            )}

            {chatError && <p className="text-xs text-destructive">{chatError}</p>}
            {chatStatus && <p className="text-xs text-primary">{chatStatus}</p>}
          </section>

          <section className="space-y-4">
            <h3 className="text-xs uppercase tracking-wider text-muted-foreground">MCP server</h3>
            <div className="space-y-2">
              <Label className="text-xs">Transport</Label>
              <Select
                value={mcp.transport}
                onValueChange={(v) => setMcp({ transport: v as 'stdio' | 'sse' })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="stdio">stdio</SelectItem>
                  <SelectItem value="sse">SSE</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {mcp.transport === 'sse' && (
              <div className="space-y-2">
                <Label className="text-xs">SSE port</Label>
                <Input
                  type="number"
                  value={mcp.ssePort}
                  onChange={(e) => setMcp({ ssePort: Number(e.target.value) })}
                />
              </div>
            )}
            <div className="space-y-2">
              <Label className="text-xs">Cursor / Claude MCP config</Label>
              <CopySnippet value={snippet} />
            </div>
          </section>

          <ConnectorsSection />

          <section className="space-y-4">
            <h3 className="text-xs uppercase tracking-wider text-muted-foreground">
              Default source filters
            </h3>
            <p className="text-xs text-muted-foreground">
              Defaults for MCP and client search/investigate calls that pass{' '}
              <code className="text-[10px]">source_types</code>. Indexed sources come from the live
              service overview; enabled connectors can be pre-toggled before the first sync.
            </p>
            {!overview.raw?.sourceTypes?.length ? (
              <p className="text-[11px] text-muted-foreground">
                Index a connector in Configure to highlight live types. Product sources are listed
                below so you can set preferences ahead of time.
              </p>
            ) : null}
            <SourceFilterCheckboxes
              sources={sourceOptions}
              values={mcp.defaultSourceFilters}
              onChange={setMcpSourceFilter}
            />
          </section>

        </div>

        <div className="shrink-0 border-t border-border px-5 py-3">
          <Button
            type="button"
            className="w-full"
            onClick={() => void handleSave()}
            disabled={isSaving || (!trialLocksLlm && !canSaveLlm && Boolean(chatSelection))}
          >
            {isSaving ? 'saving…' : 'save settings'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
