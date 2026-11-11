import { useSession } from '@taktovia/api-client';
import type { ScreenContext } from '@taktovia/design-system';
import { zeit } from '@taktovia/design-system';

/** Anzeigezone, bis der Dienst seine Zone liefert (WP-M1-09/18); Plattformstandard der Betriebe in der Anfangsregion. */
export const DEFAULT_ZONE = 'Europe/Berlin';

/** Arbeitskontext aus der autorisierten Sitzung; `status` ist der fachliche Status des jeweiligen Bildschirms. */
export function useScreenContext(status: ScreenContext['status']): ScreenContext | null {
  const session = useSession();
  if (session.status !== 'ready') return null;
  const scope = session.me.service_scope;
  return {
    tenant: session.me.tenant_name,
    service:
      scope === null ? 'Alle Dienste' : scope.length === 1 ? '1 Dienst' : `${scope.length} Dienste`,
    status,
    dataAsOf: zeit.toRfc3339(session.loadedAt, DEFAULT_ZONE),
    zone: DEFAULT_ZONE,
  };
}
