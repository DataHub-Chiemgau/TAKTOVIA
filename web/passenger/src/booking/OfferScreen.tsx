import { useSession } from '@taktovia/api-client';
import { ScreenFrame, StateView, zeit } from '@taktovia/design-system';
import type { ScreenContext } from '@taktovia/design-system';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { OfferList } from './OfferList';
import { Schritte } from './Schritte';
import type { AngebotNavState } from './StartView';
import { useOnline } from '../netz/useOnline';
import { tx } from '../texte';

const DEFAULT_ZONE = 'Europe/Berlin';

/** Route `/angebote?offer=<id>` (UI-02): die Angebots-ID steht in der Adresse, sodass die Ansicht nach Neuladen denselben Angebotsstand vom Server liest. */
export function OfferScreen({ now }: { now?: () => number } = {}) {
  const session = useSession();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const online = useOnline();
  if (session.status !== 'ready') return null;
  const nav = (location.state ?? null) as Partial<AngebotNavState> | null;
  const zone = nav?.zone ?? DEFAULT_ZONE;
  const ids = params.getAll('offer');
  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status: online ? { tone: 'info', label: 'Angebote' } : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, zone),
    zone,
  };
  return (
    <ScreenFrame title={tx('ui02.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : ids.length === 0 ? (
        <StateView
          kind="empty"
          title={tx('ui02.empty.title')}
          hint={tx('ui02.empty.hint')}
          action={
            <button type="button" onClick={() => void navigate('/')}>
              {tx('angebot.neueSuche')}
            </button>
          }
        />
      ) : (
        <>
          <Schritte aktuell="angebot" />
          <OfferList
            key={ids.join(',')}
            offerIds={ids}
            origin={nav?.origin}
            destination={nav?.destination}
            zone={zone}
            directSeconds={nav?.directSeconds}
            now={now}
            onNewSearch={() => void navigate('/')}
          />
        </>
      )}
    </ScreenFrame>
  );
}
