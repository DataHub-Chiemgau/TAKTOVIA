import { useSession } from '@taktovia/api-client';
import { ScreenFrame, StateView, zeit } from '@taktovia/design-system';
import type { ScreenContext } from '@taktovia/design-system';
import { useNavigate, useParams } from 'react-router-dom';
import { Checkout } from './Checkout';
import { Schritte } from './Schritte';
import { useOnline } from '../netz/useOnline';
import { tx } from '../texte';

const DEFAULT_ZONE = 'Europe/Berlin';

/** Route `/checkout/:offerId` (UI-03): die Angebots-ID steht in der Adresse, ein Neuladen setzt am Serverstand fort (Hold, Buchung). */
export function CheckoutScreen({ now, pollMs }: { now?: () => number; pollMs?: number } = {}) {
  const session = useSession();
  const { offerId } = useParams();
  const navigate = useNavigate();
  const online = useOnline();
  if (session.status !== 'ready') return null;
  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status: online ? { tone: 'info', label: 'Checkout' } : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, DEFAULT_ZONE),
    zone: DEFAULT_ZONE,
  };
  return (
    <ScreenFrame title={tx('ui03.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : !offerId ? (
        <StateView kind="empty" title={tx('ui03.empty.title')} hint={tx('ui03.empty.hint')} />
      ) : (
        <>
          <Schritte aktuell="buchen" />
          <Checkout
            offerId={offerId}
            zone={DEFAULT_ZONE}
            now={now}
            {...(pollMs === undefined ? {} : { pollMs })}
            onNewSearch={() => void navigate('/')}
          />
        </>
      )}
    </ScreenFrame>
  );
}
