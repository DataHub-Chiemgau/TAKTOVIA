import { useState } from 'react';
import { useSession } from '@taktovia/api-client';
import { ScreenFrame, StateView, zeit } from '@taktovia/design-system';
import type { ScreenContext } from '@taktovia/design-system';
import { Ortswahl, toRidePlace } from './Ortswahl';
import type { PlaceChoice } from './Ortswahl';
import { tx } from '../texte';
import { useOnline } from '../netz/useOnline';

const ZONE = 'Europe/Berlin';

/**
 * UI-01 Startansicht, Ortswahl-Teil (WP-M2-38): Start und Ziel über die Ortssuche mit fünf Eingabearten. Zeit, Reisende, Verfügbarkeit und die Buchung selbst folgen
 * mit WP-M2-37 (UI-01 Buchung in fünf Schritten); hier endet der Weg bei den bestätigten Orten, die in `POST /v1/ride-offers` als `origin`/`destination` verwendet werden.
 */
export function Start() {
  const session = useSession();
  const [origin, setOrigin] = useState<PlaceChoice | null>(null);
  const [destination, setDestination] = useState<PlaceChoice | null>(null);
  const online = useOnline();
  if (session.status !== 'ready') return null;
  // Genau ein Dienst im Dienstbezug: für Bediengebiet und Haltepunktzuordnung verwenden; sonst ohne Zuordnung suchen.
  const scope = session.me.service_scope;
  const serviceId = scope && scope.length === 1 ? scope[0] : undefined;
  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service: scope === null ? 'Alle Dienste' : `${scope.length} Dienste`,
    status: online ? { tone: 'info', label: 'Ortswahl' } : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, ZONE),
    zone: ZONE,
  };
  const ready =
    origin && destination && !origin.outsideServiceArea && !destination.outsideServiceArea;
  return (
    <ScreenFrame title={tx('ui01.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : (
        <>
          <Ortswahl label="Start" serviceId={serviceId} value={origin} onChange={setOrigin} />
          <Ortswahl
            label="Ziel"
            serviceId={serviceId}
            value={destination}
            onChange={setDestination}
          />
          {ready ? (
            <p
              role="status"
              data-testid="orte-bereit"
              data-origin={JSON.stringify(toRidePlace(origin))}
              data-destination={JSON.stringify(toRidePlace(destination))}
            >
              Start und Ziel sind bestätigt. Die Buchung folgt mit der Fahrtsuche.
            </p>
          ) : (
            <StateView
              kind="empty"
              title="Start und Ziel wählen"
              hint="Wählen Sie Start und Ziel, um eine Fahrt zu suchen."
            />
          )}
        </>
      )}
    </ScreenFrame>
  );
}
