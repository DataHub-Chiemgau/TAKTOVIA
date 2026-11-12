import { useEffect, useState } from 'react';
import { useApi } from '@taktovia/api-client';

export interface DienstAuswahl {
  status: 'loading' | 'ready' | 'none';
  ids: readonly string[];
  /** Name des Dienstes, soweit lesbar (Fahrgäste dürfen `GET /v1/services` nicht aufrufen; dann `Dienst n`). */
  names: Readonly<Record<string, string>>;
}

/**
 * Dienste, für die der Fahrgast suchen darf: der Dienstbezug aus `/v1/me`; ohne Einschränkung (`null`) wird die Dienstliste versucht. Gelingt auch das nicht,
 * gibt es keinen Dienst (Zustand „kein Dienst“), und es wird nie ein Dienst erraten.
 */
export function useDienste(scope: readonly string[] | null | undefined): DienstAuswahl {
  const api = useApi();
  const [listed, setListed] = useState<DienstAuswahl | null>(null);
  useEffect(() => {
    if (scope !== null) return;
    let alive = true;
    void api
      .call('listServices', { query: { limit: 50 } })
      .then((page) => {
        if (!alive) return;
        setListed({
          status: page.items.length > 0 ? 'ready' : 'none',
          ids: page.items.map((s) => s.id),
          names: Object.fromEntries(page.items.map((s) => [s.id, s.name])),
        });
      })
      .catch(() => {
        if (alive) setListed({ status: 'none', ids: [], names: {} });
      });
    return () => {
      alive = false;
    };
  }, [api, scope]);
  if (scope === undefined) return { status: 'loading', ids: [], names: {} };
  if (scope !== null) return { status: scope.length > 0 ? 'ready' : 'none', ids: scope, names: {} };
  return listed ?? { status: 'loading', ids: [], names: {} };
}
