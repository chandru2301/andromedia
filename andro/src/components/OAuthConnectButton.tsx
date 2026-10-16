import { useEffect, useRef, useState } from 'react';
import { Check, ExternalLink, LoaderCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  getOAuthStatus,
  oauthAuthorizeUrl,
  type OAuthProviderId,
  type OAuthStatus,
} from '@/lib/andromediaApi';

interface OAuthConnectButtonProps {
  provider: OAuthProviderId;
  label: string;
  /** Optional provider-specific description shown before the OAuth flow starts. */
  description?: string;
  /** Called with every status refresh — lets a parent read provider-specific metadata (e.g. Zoho's connectionId). */
  onStatusChange?: (status: OAuthStatus) => void;
}

const PROVIDER_COPY: Record<OAuthProviderId, { connect: string; connected: string }> = {
  slack: { connect: 'Connect Slack', connected: 'Slack connected' },
  notion: { connect: 'Connect Notion', connected: 'Notion connected' },
  google_chat: { connect: 'Connect Google Chat', connected: 'Google Chat connected' },
  linear: { connect: 'Connect Linear', connected: 'Linear connected' },
  zoho: { connect: 'Connect Zoho', connected: 'Zoho connected' },
  jira: { connect: 'Connect Jira', connected: 'Jira connected' },
};

/**
 * "Click Login, come back connected" — replaces pasting a bot token / integration secret by hand.
 * Opens the provider's consent screen in the system browser, then polls the backend until the
 * OAuth callback has stored a token (see andromedia/core .../oauth/OAuthService.java).
 */
export function OAuthConnectButton({ provider, label, description, onStatusChange }: OAuthConnectButtonProps) {
  const copy = PROVIDER_COPY[provider];
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [connected, setConnected] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = async () => {
    try {
      const status = await getOAuthStatus(provider);
      setConfigured(status.configured);
      setConnected(status.connected);
      setError(null);
      onStatusChange?.(status);
      return status;
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Failed to check connection status');
      return null;
    }
  };

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [provider]);

  useEffect(() => {
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
      }
    };
  }, []);

  const startConnect = () => {
    window.open(oauthAuthorizeUrl(provider), '_blank');
    setWaiting(true);
    if (pollRef.current) {
      clearInterval(pollRef.current);
    }
    let attempts = 0;
    pollRef.current = setInterval(async () => {
      attempts += 1;
      const status = await refresh();
      if (status?.connected || attempts >= 60) {
        setWaiting(false);
        if (pollRef.current) {
          clearInterval(pollRef.current);
        }
      }
    }, 2000);
  };

  if (connected) {
    return (
      <div className="flex items-center justify-between rounded-md border border-primary/30 bg-primary/10 px-3 py-2 text-xs text-foreground">
        <span className="flex items-center gap-1.5 font-medium">
          <Check className="h-3.5 w-3.5 text-primary" />
          {copy.connected}
        </span>
        <Button type="button" variant="outline" size="sm" onClick={startConnect}>
          Reconnect
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {description ? <p className="text-xs leading-relaxed text-muted-foreground">{description}</p> : null}
      <Button
        type="button"
        variant="outline"
        className="w-full justify-center"
        onClick={startConnect}
        disabled={configured === false || waiting}
      >
        {waiting ? (
          <>
            <LoaderCircle className="h-4 w-4 animate-spin" />
            Waiting for {label}…
          </>
        ) : (
          <>
            {copy.connect}
            <ExternalLink className="h-3.5 w-3.5" />
          </>
        )}
      </Button>
      {configured === false && (
        <p className="text-[11px] text-muted-foreground">
          {label} login isn&apos;t configured on this server yet — ask your admin to set the OAuth
          client id/secret.
        </p>
      )}
      {error && <p className="text-[11px] text-destructive">{error}</p>}
    </div>
  );
}
