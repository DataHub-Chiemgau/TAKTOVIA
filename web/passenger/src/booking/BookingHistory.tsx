import { useCallback, useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { RideBooking } from '@taktovia/api-client';
import { ErrorState, useAnnounce } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { stateText } from './BookingDetail';
import { formatMoney } from './geld';
import { fenster } from './Zeitanzeige';
import { tx } from '../texte';
import type { TextKey } from '../texte';

/** Die vier Bereiche der Übersicht, in der Reihenfolge der Reiter; die Einteilung trifft allein der Server (`bereich` der Buchung, Filter `bereich`). */
export const BEREICHE = ['bevorstehend', 'laufend', 'vergangen', 'storniert'] as const;
export type Bereich = (typeof BEREICHE)[number];

interface Gruppe {
  items: RideBooking[];
  /** Es gibt weitere Seiten, die diese Ansicht nicht lädt. */
  mehr: boolean;
}
type Daten = Record<Bereich, Gruppe>;

/** Sortierung je Bereich: bevorstehend und laufend nach der nächsten Abholung, vergangen und storniert nach der jüngsten Abholung zuerst. */
export function sortiere(bereich: Bereich, items: readonly RideBooking[]): RideBooking[] {
  const key = (b: RideBooking) => Date.parse(b.promise.pickup_earliest);
  const asc = bereich === 'bevorstehend' || bereich === 'laufend';
  return [...items].sort((a, b) => (asc ? key(a) - key(b) : key(b) - key(a)));
}

export interface BookingHistoryProps {
  zone: string;
  /** Abstand der Abfragen des Änderungsstroms in ms. */
  pollMs?: number;
  /** Abstand der vollständigen Aktualisierung in ms (Statuswechsel ohne Planänderung, z. B. Fahrtbeginn und -ende). */
  refreshMs?: number;
}

/**
 * UI-04 Übersicht in vier Bereichen (WP-M2-45, LH-6.3-PAX-008): Reiter mit Anzahl, Sortierung je Bereich, Leerzustand je Bereich, Fehlerzustand. Jeder Bereich wird mit dem
 * Serverfilter `bereich` gelesen, die Oberfläche teilt nie selbst ein. Ein Statuswechsel verschiebt die Buchung ohne manuelles Neuladen: der Änderungsstrom der Planrevisionen löst die
 * Aktualisierung aus, dazu eine regelmäßige Aktualisierung für Wechsel ohne Planänderung; die Verschiebung wird über die zurückhaltende Live-Region angesagt.
 */
export function BookingHistory({ zone, pollMs = 5000, refreshMs = 30000 }: BookingHistoryProps) {
  const api = useApi();
  const announce = useAnnounce();
  const [daten, setDaten] = useState<Daten | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [aktiv, setAktiv] = useState<Bereich>('bevorstehend');
  const bekannt = useRef<Map<string, { bereich: Bereich; nummer: string }>>(new Map());
  const cursor = useRef(0);
  const tabs = useRef<Record<string, HTMLButtonElement | null>>({});

  const laden = useCallback(async () => {
    const teile = await Promise.all(
      BEREICHE.map((b) => api.call('listBookings', { query: { bereich: b, limit: 100 } })),
    );
    const next = Object.fromEntries(
      BEREICHE.map((b, i) => [
        b,
        { items: teile[i]?.items ?? [], mehr: Boolean(teile[i]?.next_cursor) },
      ]),
    ) as Daten;
    // Verschiebungen zwischen den Bereichen ansagen (nicht beim ersten Laden)
    const jetzt = new Map<string, { bereich: Bereich; nummer: string }>();
    for (const b of BEREICHE)
      for (const x of next[b].items) jetzt.set(x.id, { bereich: b, nummer: x.booking_number });
    if (bekannt.current.size > 0)
      for (const [id, v] of jetzt) {
        const vorher = bekannt.current.get(id);
        if (vorher && vorher.bereich !== v.bereich)
          announce(
            `${v.nummer} ${tx('reisen.verschoben')} ${tx(`reisen.${v.bereich}` as TextKey)}`,
          );
      }
    bekannt.current = jetzt;
    setDaten(next);
    setError(null);
  }, [api, announce]);

  useEffect(() => {
    let alive = true;
    let stream: ReturnType<typeof setTimeout> | undefined;
    let refresh: ReturnType<typeof setTimeout> | undefined;
    const sicher = (p: Promise<void>) =>
      p.catch((e: unknown) => {
        if (alive) setError(e instanceof ApiError ? e : new ApiError(0));
      });
    const tickStrom = async () => {
      try {
        const feed = await api.call('listPlanChanges', { query: { after: cursor.current } });
        cursor.current = Math.max(cursor.current, feed.next_cursor);
        // jeder Eintrag zu einer eigenen Buchung (Einfügen, Entfernen, Zeitänderung) kann den Bereich ändern: neu lesen
        if (feed.items.length > 0 && alive) await laden();
      } catch {
        // Verbindungsunterbrechung: die regelmäßige Aktualisierung lädt nach
      }
      if (alive) stream = setTimeout(() => void tickStrom(), pollMs);
    };
    const tickRefresh = async () => {
      await laden().catch(() => undefined);
      if (alive) refresh = setTimeout(() => void tickRefresh(), refreshMs);
    };
    const start = async () => {
      const snap = await api.call('getPlanChangesSnapshot', {}).catch(() => null);
      cursor.current = snap?.cursor ?? 0;
      await laden();
      if (alive) {
        stream = setTimeout(() => void tickStrom(), pollMs);
        refresh = setTimeout(() => void tickRefresh(), refreshMs);
      }
    };
    void sicher(start());
    const sichtbar = () => {
      if (document.visibilityState === 'visible') void laden().catch(() => undefined);
    };
    document.addEventListener('visibilitychange', sichtbar);
    return () => {
      alive = false;
      if (stream) clearTimeout(stream);
      if (refresh) clearTimeout(refresh);
      document.removeEventListener('visibilitychange', sichtbar);
    };
  }, [api, laden, pollMs, refreshMs]);

  const taste = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    const ziel =
      e.key === 'ArrowRight'
        ? (i + 1) % BEREICHE.length
        : e.key === 'ArrowLeft'
          ? (i + BEREICHE.length - 1) % BEREICHE.length
          : e.key === 'Home'
            ? 0
            : e.key === 'End'
              ? BEREICHE.length - 1
              : -1;
    if (ziel < 0) return;
    e.preventDefault();
    const b = BEREICHE[ziel];
    if (b) {
      setAktiv(b);
      tabs.current[b]?.focus();
    }
  };

  if (error && !daten)
    return (
      <ErrorState
        error={error}
        action={
          <button
            type="button"
            onClick={() =>
              void laden().catch((e: unknown) =>
                setError(e instanceof ApiError ? e : new ApiError(0)),
              )
            }
          >
            {tx('reisen.nochmal')}
          </button>
        }
      />
    );
  if (!daten) return <p aria-busy="true">{tx('reisen.laedt')}</p>;

  const gruppe = daten[aktiv];
  return (
    <section aria-label={tx('reisen.bereiche')} data-testid="reisen-uebersicht">
      <div role="tablist" aria-label={tx('reisen.bereiche')}>
        {BEREICHE.map((b, i) => (
          <button
            key={b}
            ref={(el) => {
              tabs.current[b] = el;
            }}
            type="button"
            role="tab"
            id={`tab-${b}`}
            aria-selected={aktiv === b}
            aria-controls={`panel-${b}`}
            tabIndex={aktiv === b ? 0 : -1}
            data-testid={`tab-${b}`}
            data-count={daten[b].items.length}
            onClick={() => setAktiv(b)}
            onKeyDown={(e) => taste(e, i)}
          >
            {tx(`reisen.${b}` as TextKey)} ({daten[b].items.length}
            {daten[b].mehr ? '+' : ''})
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`panel-${aktiv}`}
        aria-labelledby={`tab-${aktiv}`}
        tabIndex={0}
        data-testid="reisen-panel"
        data-bereich={aktiv}
      >
        {gruppe.items.length === 0 ? (
          <p data-testid="reisen-leer">{tx(`reisen.leer.${aktiv}` as TextKey)}</p>
        ) : (
          <ul>
            {sortiere(aktiv, gruppe.items).map((b) => (
              <li key={b.id} data-booking-id={b.id} data-bereich={b.bereich}>
                <Link to={`/reisen/${b.id}`}>{b.booking_number}</Link>: {stateText(b.state)};{' '}
                {tx('reisen.abholung')}{' '}
                {fenster(
                  { earliest_at: b.promise.pickup_earliest, latest_at: b.promise.pickup_latest },
                  zone,
                )}
                ; {formatMoney(b.promise.gross)}
              </li>
            ))}
          </ul>
        )}
        {gruppe.mehr ? <p>{tx('reisen.mehr')}</p> : null}
      </div>
      {error ? <p role="alert">{tx('reisen.fehler')}</p> : null}
    </section>
  );
}
