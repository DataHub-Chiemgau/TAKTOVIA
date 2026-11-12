import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { RideOfferRequest, RideRejectionCode } from '@taktovia/api-client';
import {
  BusyButton,
  DateTimeField,
  ErrorState,
  ScreenFrame,
  StateView,
  zeit,
} from '@taktovia/design-system';
import type { DateTimeValue, ScreenContext } from '@taktovia/design-system';
import { useNavigate } from 'react-router-dom';
import { StandortFreigabe } from './LocationConsent';
import type { DevicePosition } from './LocationConsent';
import { Ortswahl, fromHit, toRidePlace } from './Ortswahl';
import type { PlaceChoice } from './Ortswahl';
import { RejectionMessage } from './RejectionMessage';
import { Schritte } from './Schritte';
import { AnstehendeBuchungen } from './AnstehendeBuchungen';
import { Verfuegbarkeit } from './Verfuegbarkeit';
import { useDienste } from './useDienst';
import { useCommands } from '../api/useCommands';
import { useOnline } from '../netz/useOnline';
import { tx } from '../texte';

const DEFAULT_ZONE = 'Europe/Berlin';

/** Ablehnungsgründe, die als „Keine Abdeckung“ (Gebiet, Betriebszeit, Haltepunkt) dargestellt werden (Kap. 06 §6.2). */
const KEINE_ABDECKUNG: ReadonlySet<RideRejectionCode> = new Set([
  'OUTSIDE_AREA',
  'OUTSIDE_HOURS',
  'NO_STOP_IN_RANGE',
  'NO_ACCESSIBLE_STOP',
  'RULE_FORBIDDEN',
]);

/** Router-Zustand, den UI-01 an UI-02 übergibt (Orte für die Fußwegangabe, Zone, Fahrtdauer der Planung). */
export interface AngebotNavState {
  origin: RideOfferRequest['origin'];
  destination: RideOfferRequest['destination'];
  zone: string;
  directSeconds: Record<string, number>;
}

type Phase =
  | { kind: 'input' }
  | { kind: 'searching' }
  | { kind: 'rejected'; code: RideRejectionCode }
  | { kind: 'cancelled' }
  | { kind: 'error'; error: ApiError };

/**
 * UI-01 Startansicht (WP-M2-37, LH-6.3-PAX-001): Start und Ziel (Ortssuche, WP-M2-38; Standort nur nach Zustimmung, WP-M2-39), Reisezeit mit Zeitzone und Offsetwahl,
 * Reisende und Unterstützung, Verfügbarkeit des Dienstes (Betriebsstatus) und eigene anstehende Buchungen. Eine Standardfahrt (ein Fahrgast, Sofortfahrt) braucht
 * höchstens vier Hauptschritte (siehe `schritte.ts`). Die Suche ist abbrechbar und bucht nie; gebucht wird erst in UI-02 mit „Verbindlich buchen“.
 */
export function StartView({ now = Date.now }: { now?: () => number } = {}) {
  const session = useSession();
  const api = useApi();
  const commands = useCommands();
  const navigate = useNavigate();
  const online = useOnline();
  const scope = session.status === 'ready' ? session.me.service_scope : undefined;
  const dienste = useDienste(scope);
  const [serviceChoice, setServiceChoice] = useState<string | null>(null);
  const serviceId = serviceChoice ?? dienste.ids[0];
  const [zone, setZone] = useState(DEFAULT_ZONE);
  const [origin, setOrigin] = useState<PlaceChoice | null>(null);
  const [destination, setDestination] = useState<PlaceChoice | null>(null);
  const [bias, setBias] = useState<{ latitude: number; longitude: number } | null>(null);
  const [focusStart, setFocusStart] = useState(0);
  const [sofort, setSofort] = useState(true);
  const [timeKind, setTimeKind] = useState<'DEPARTURE' | 'ARRIVAL'>('DEPARTURE');
  const [dt, setDt] = useState<DateTimeValue & { instant: string | null }>({
    local: '',
    offset: null,
    instant: null,
  });
  const [passengers, setPassengers] = useState(1);
  const [wheelchair, setWheelchair] = useState(false);
  const [phase, setPhase] = useState<Phase>({ kind: 'input' });
  const abort = useRef<AbortController | null>(null);
  if (session.status !== 'ready') return null;

  /** Bestätigter Standort wird zum Start (Adresse über die Ortssuche); eine inzwischen von Hand gewählte Wahl wird nie überschrieben. */
  const confirmPosition = async (position: DevicePosition) => {
    const { latitude, longitude } = position;
    setBias({ latitude, longitude });
    let choice: PlaceChoice = {
      type: 'MAP_POINT',
      label: tx('standort.meinStandort'),
      latitude,
      longitude,
      stopId: null,
      outsideServiceArea: false,
      nearestStop: null,
    };
    try {
      const r = await api.call('searchPlaces', {
        query: {
          map_latitude: latitude,
          map_longitude: longitude,
          ...(serviceId ? { service_id: serviceId } : {}),
        },
      });
      const hit = r.items[0];
      if (hit) choice = { ...fromHit(hit), label: hit.source === 'map' ? choice.label : hit.label };
    } catch {
      // Ortssuche nicht erreichbar: der Standort bleibt als Kartenpunkt nutzbar
    }
    setOrigin((current) => current ?? choice);
  };

  const orteBereit =
    origin !== null &&
    destination !== null &&
    !origin.outsideServiceArea &&
    !destination.outsideServiceArea;
  const nowInstant = zeit.toRfc3339(now(), zone);
  const desiredAt = sofort ? nowInstant : dt.instant;
  const kannSuchen = orteBereit && serviceId !== undefined && desiredAt !== null && online;

  const suchen = async () => {
    if (!orteBereit || !serviceId || !origin || !destination) return;
    const desired = sofort ? zeit.toRfc3339(now(), zone) : dt.instant;
    if (!desired) return;
    const body: RideOfferRequest = {
      service_id: serviceId,
      origin: toRidePlace(origin),
      destination: toRidePlace(destination),
      time_kind: sofort ? 'DEPARTURE' : timeKind,
      desired: { desired_at: desired, time_zone: zone },
      passengers,
      ...(wheelchair ? { wheelchair_spaces: 1 } : {}),
      channel: 'WEB',
    };
    const controller = new AbortController();
    abort.current = controller;
    setPhase({ kind: 'searching' });
    try {
      // gleicher Inhalt = gleiche Absicht = gleicher Idempotency-Key (Wiederholung nach Verbindungsabbruch erzeugt keinen zweiten Versuch)
      const r = await commands.run(`search:${JSON.stringify(body)}`, 'createRideOffer', {
        body,
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (r.rejection) {
        setPhase({ kind: 'rejected', code: r.rejection.code });
        return;
      }
      const state: AngebotNavState = {
        origin: body.origin,
        destination: body.destination,
        zone,
        directSeconds: Object.fromEntries(r.offers.map((o) => [o.id, o.direct_seconds])),
      };
      const params = new URLSearchParams();
      for (const o of r.offers) params.append('offer', o.id);
      void navigate(`/angebote?${params.toString()}`, { state });
    } catch (e) {
      if (controller.signal.aborted) return;
      setPhase({ kind: 'error', error: e instanceof ApiError ? e : new ApiError(0) });
    } finally {
      abort.current = null;
    }
  };

  const abbrechen = () => {
    abort.current?.abort();
    setPhase({ kind: 'cancelled' });
  };

  const context: ScreenContext = {
    tenant: session.me.tenant_name,
    service:
      session.me.service_scope === null
        ? 'Alle Dienste'
        : `${session.me.service_scope.length} Dienste`,
    status: online
      ? { tone: 'info', label: 'Fahrt suchen' }
      : { tone: 'warning', label: 'Offline' },
    dataAsOf: zeit.toRfc3339(session.loadedAt, zone),
    zone,
  };

  let hint: ReactNode = null;
  if (!orteBereit) hint = tx('start.orteFehlen');
  else if (desiredAt === null) hint = tx('start.zeitFehlt');

  return (
    <ScreenFrame title={tx('ui01.title')} context={context}>
      {!online ? (
        <StateView kind="offline" title={tx('offline.title')} hint={tx('offline.hint')} />
      ) : dienste.status === 'none' ? (
        <StateView kind="empty" title={tx('start.keineFahrt')} hint={tx('start.keinDienst')} />
      ) : (
        <>
          <Schritte aktuell={orteBereit ? 'zeit' : 'orte'} />
          {serviceId ? <Verfuegbarkeit serviceId={serviceId} onZone={setZone} /> : null}
          {dienste.ids.length > 1 ? (
            <p>
              <label>
                {tx('start.dienst')}{' '}
                <select
                  value={serviceId ?? ''}
                  onChange={(e) => {
                    setServiceChoice(e.target.value);
                    setZone(DEFAULT_ZONE);
                  }}
                >
                  {dienste.ids.map((id, i) => (
                    <option key={id} value={id}>
                      {dienste.names[id] ?? `${tx('start.dienst')} ${i + 1}`}
                    </option>
                  ))}
                </select>
              </label>
            </p>
          ) : null}
          <StandortFreigabe
            startChosen={origin !== null}
            onConfirm={(p) => void confirmPosition(p)}
            onManual={() => setFocusStart((n) => n + 1)}
          />
          <Ortswahl
            label="Start"
            serviceId={serviceId}
            bias={bias}
            focusToken={focusStart}
            value={origin}
            onChange={setOrigin}
          />
          <Ortswahl
            label="Ziel"
            serviceId={serviceId}
            bias={bias}
            value={destination}
            onChange={setDestination}
          />
          {orteBereit ? (
            <p
              role="status"
              data-testid="orte-bereit"
              data-origin={JSON.stringify(toRidePlace(origin))}
              data-destination={JSON.stringify(toRidePlace(destination))}
            >
              {tx('start.bereit')}
            </p>
          ) : null}
          <fieldset>
            <legend>{tx('start.wann')}</legend>
            <label>
              <input type="radio" name="wann" checked={sofort} onChange={() => setSofort(true)} />{' '}
              {tx('start.jetzt')}
            </label>{' '}
            <label>
              <input type="radio" name="wann" checked={!sofort} onChange={() => setSofort(false)} />{' '}
              {tx('start.zeitWaehlen')}
            </label>
            {sofort ? (
              <p data-testid="zone-sofort">
                {tx('start.zeitzone')}: {zone} (UTC{nowInstant.slice(19)})
              </p>
            ) : (
              <>
                <fieldset>
                  <legend>{tx('start.zeitbezug')}</legend>
                  <label>
                    <input
                      type="radio"
                      name="zeitbezug"
                      checked={timeKind === 'DEPARTURE'}
                      onChange={() => setTimeKind('DEPARTURE')}
                    />{' '}
                    {tx('start.abfahrt')}
                  </label>{' '}
                  <label>
                    <input
                      type="radio"
                      name="zeitbezug"
                      checked={timeKind === 'ARRIVAL'}
                      onChange={() => setTimeKind('ARRIVAL')}
                    />{' '}
                    {tx('start.ankunft')}
                  </label>
                </fieldset>
                <DateTimeField
                  label={tx('start.datumUhrzeit')}
                  zone={zone}
                  value={dt}
                  onChange={setDt}
                />
              </>
            )}
          </fieldset>
          <p>
            <label>
              {tx('start.reisende')}{' '}
              <input
                type="number"
                min={1}
                max={99}
                value={passengers}
                onChange={(e) =>
                  setPassengers(Math.min(99, Math.max(1, Math.trunc(Number(e.target.value)) || 1)))
                }
              />
            </label>
          </p>
          <p>
            <label>
              <input
                type="checkbox"
                checked={wheelchair}
                onChange={(e) => setWheelchair(e.target.checked)}
              />{' '}
              {tx('start.rollstuhl')}
            </label>
          </p>
          {hint ? <p data-testid="such-hinweis">{hint}</p> : null}
          <p>
            <BusyButton onAction={suchen} locked={!kannSuchen || phase.kind === 'searching'}>
              {tx('start.suchen')}
            </BusyButton>{' '}
            {phase.kind === 'searching' ? (
              <button type="button" onClick={abbrechen}>
                {tx('start.abbrechen')}
              </button>
            ) : null}
          </p>
          <div aria-live="polite">
            {phase.kind === 'searching' ? <p role="status">{tx('start.suchtLaeuft')}</p> : null}
            {phase.kind === 'cancelled' ? <p role="status">{tx('start.abgebrochen')}</p> : null}
          </div>
          {phase.kind === 'rejected' ? (
            <section aria-labelledby="ablehnung-h" data-testid="ablehnung">
              <h2 id="ablehnung-h">
                {KEINE_ABDECKUNG.has(phase.code)
                  ? tx('start.keineAbdeckung')
                  : tx('start.keineFahrt')}
              </h2>
              <RejectionMessage code={phase.code} />
            </section>
          ) : null}
          {phase.kind === 'error' ? (
            <ErrorState
              error={phase.error}
              action={
                <BusyButton onAction={suchen} locked={!kannSuchen}>
                  {tx('start.suchen')}
                </BusyButton>
              }
            />
          ) : null}
          <AnstehendeBuchungen zone={zone} />
        </>
      )}
    </ScreenFrame>
  );
}
