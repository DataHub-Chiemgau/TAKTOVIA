import { useMySessions } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, SessionList, zeit } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import type { SessionResource } from '@taktovia/api-client';
import { DEFAULT_ZONE, useScreenContext } from './ScreenContext';

/** UI-05 Basis (Konto): eigene Sitzungen im aktiven Mandanten, einzelne Geräte oder alle Geräte abmelden (WP-M1-10, WP-M1-44). */
export function Konto() {
  const sessions = useMySessions();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Sitzungen' });
  if (!context) return null;
  const state: ResourceState<SessionResource[]> =
    sessions.state.status === 'ready'
      ? { status: 'ready', data: sessions.state.items }
      : sessions.state.status === 'error'
        ? { status: 'error', error: sessions.state.error }
        : { status: 'loading' };
  return (
    <ScreenFrame title="Konto" context={context}>
      {sessions.notice ? <p role="status">{sessions.notice}</p> : null}
      <ResourceView
        state={state}
        isEmpty={(d) => d.length === 0}
        empty={{ title: 'Keine Sitzungen' }}
        errorAction={
          <button type="button" onClick={sessions.reload}>
            Erneut versuchen
          </button>
        }
      >
        {(items) => (
          <SessionList
            sessions={items}
            currentId={sessions.currentId}
            formatTime={(iso) => zeit.toRfc3339(Date.parse(iso), DEFAULT_ZONE)}
            onRevoke={sessions.revoke}
            onRevokeAll={sessions.revokeAll}
          />
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
