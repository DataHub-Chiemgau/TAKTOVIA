import { useSession } from '@taktovia/api-client';
import { ScreenFrame, StateView, zeit } from '@taktovia/design-system';
import type { ScreenContext } from '@taktovia/design-system';
import { useParams } from 'react-router-dom';
import { BookingDetail } from './BookingDetail';
import { BookingHistory } from './BookingHistory';
import { WalkingRoute } from './WalkingRoute';
import { useOnline } from '../netz/useOnline';
import { tx } from '../texte';

const DEFAULT_ZONE = 'Europe/Berlin';

/** Route `/reisen/:bookingId` (UI-04 Detail). */
export function ReiseDetailScreen({ pollMs }: { pollMs?: number } = {}) {
  const session = useSession();
  const { bookingId } = useParams();
  const online = useOnline();
  if (session.status !== 'ready') return null;
  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status: online ? { tone: 'info', label: 'Reise' } : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, DEFAULT_ZONE),
    zone: DEFAULT_ZONE,
  };
  return (
    <ScreenFrame title={tx('ui04.detail.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : !bookingId ? (
        <StateView kind="notFound" />
      ) : (
        <BookingDetail
          bookingId={bookingId}
          zone={DEFAULT_ZONE}
          {...(pollMs === undefined ? {} : { pollMs })}
        >
          {(b) => <WalkingRoute booking={b} zone={DEFAULT_ZONE} />}
        </BookingDetail>
      )}
    </ScreenFrame>
  );
}

/** Route `/reisen` (UI-04 Übersicht in vier Bereichen). */
export function ReiseUebersichtScreen({
  pollMs,
  refreshMs,
}: { pollMs?: number; refreshMs?: number } = {}) {
  const session = useSession();
  const online = useOnline();
  if (session.status !== 'ready') return null;
  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status: online ? { tone: 'info', label: 'Reisen' } : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, DEFAULT_ZONE),
    zone: DEFAULT_ZONE,
  };
  return (
    <ScreenFrame title={tx('ui04.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : (
        <BookingHistory
          zone={DEFAULT_ZONE}
          {...(pollMs === undefined ? {} : { pollMs })}
          {...(refreshMs === undefined ? {} : { refreshMs })}
        />
      )}
    </ScreenFrame>
  );
}
