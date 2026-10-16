import { useEffect, useState } from 'react';
import {
  getChatProviderSelection,
  resolveService,
  updateChatProvider,
  type ChatProviderId,
  type ChatProviderSelection,
} from '@/lib/andromediaApi';

export function useChatProvider(trialId?: string) {
  const resolved = resolveService(trialId);
  const [selection, setSelection] = useState<ChatProviderSelection | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;
    getChatProviderSelection({ trialId: resolved })
      .then((next) => {
        if (isMounted) {
          setSelection(next);
          setError(null);
        }
      })
      .catch((caught) => {
        if (isMounted) {
          setError(caught instanceof Error ? caught.message : 'Provider lookup failed');
        }
      })
      .finally(() => {
        if (isMounted) {
          setLoading(false);
        }
      });
    return () => {
      isMounted = false;
    };
  }, [resolved]);

  const select = async (provider: ChatProviderId) => {
    if (loading || provider === selection?.activeProvider) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setSelection(await updateChatProvider(provider, { trialId: resolved }));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Provider update failed');
    } finally {
      setLoading(false);
    }
  };

  return { selection, loading, error, select };
}
