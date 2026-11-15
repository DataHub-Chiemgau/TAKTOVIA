import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { LiveDashboard as LiveData, LiveTile } from '@taktovia/api-client';
import { ScreenFrame, StatusBadge, useAnnounce } from '@taktovia/design-system';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const TITLES: Record<LiveTile['id'], string> = {
  open_requests: 'Offene Anfragen',
  available_capacity: 'Verfügbare Kapazität',
  critical_delays: 'Kritische Verspätungen',
  vehicle_outages: 'Fahrzeugausfälle',
};
const HINTS: Record<LiveTile['id'], string> = {
  open_requests: 'Angebote, die noch gelten und nicht gebucht sind.',
  available_capacity: 'Fahrzeuge in laufender Schicht, nicht gesperrt.',
  critical_delays: 'Bestätigte Abholungen, die über die Toleranz hinaus überfällig sind.',
  vehicle_outages: 'Fahrzeuge mit offener Sperre.',
};
const nf = new Intl.NumberFormat('de-DE');

/** Datenalter in Sekunden als verständlicher Text („vor 45 s“, „vor 12 min“). */
export function formatAge(seconds: number): string {
  if (seconds < 90) return `vor ${seconds} s`;
  if (seconds < 5400) return `vor ${Math.round(seconds / 60)} min`;
  return `vor ${Math.round(seconds / 3600)} h`;
}

const num = (v: unknown): number | null => (typeof v === 'number' ? v : null);

function tileText(t: LiveTile): string {
  if (t.value === null) return 'kein Wert';
  if (t.id === 'available_capacity') {
    const seats = num(t.details.seats);
    const wc = num(t.details.wheelchair_spaces);
    return `${nf.format(t.value)} Fahrzeuge, ${seats === null ? '–' : nf.format(seats)} Sitze, ${wc === null ? '–' : nf.format(wc)} Rollstuhlplätze`;
  }
  if (t.id === 'vehicle_outages') {
    const long = num(t.details.long_outages);
    return `${nf.format(t.value)}${long === null ? '' : ` (davon ${nf.format(long)} lang)`}`;
  }
  return nf.format(t.value);
}

/**
 * Live-Dashboard (WP-M4-60, KPI-014): vier getrennte Kacheln (offene Anfragen, verfügbare Kapazität, kritische Verspätungen, Fahrzeugausfälle) aus `GET /v1/analytics/metrics?breakdown=live`. Transport ist
 * Polling im Abstand `refresh_seconds` (definierte Aktualisierungsfrist, Planungsannahme D-05). Jede Kachel zeigt ihren Datenstand und das Datenalter; ein veralteter oder fehlender Datenstand ist deutlich
 * gekennzeichnet (nie ein scheinbar aktueller Wert), bei fehlgeschlagener Aktualisierung bleiben die letzten Werte mit Warnung stehen. Aktualisierungen ersetzen nur den Text der Kacheln (stabile Elemente,
 * kein Fokus- oder Scrollsprung); Änderungen werden höflich über die Live-Region gemeldet, nur bei einer Wertänderung.
 */
export function LiveDashboard() {
  const api = useApi();
  const session = useSession();
  const announce = useAnnounce();
  const context = useScreenContext({ tone: 'info', label: 'Live-Betrieb' });
  const canRead =
    session.status === 'ready' && (session.me.scopes ?? []).includes('analytics:read');
  const [data, setData] = useState<LiveData | undefined>();
  const [failed, setFailed] = useState<{ at: string; status: number } | undefined>();
  const [loading, setLoading] = useState(true);
  const previous = useRef<Record<string, string>>({});

  const poll = useCallback(() => {
    api
      .call('getAnalyticsMetrics', { query: { breakdown: 'live' } })
      .then((r) => {
        if (!r.live) return;
        const live = r.live;
        setData(live);
        setFailed(undefined);
        setLoading(false);
        const changes: string[] = [];
        for (const t of live.tiles) {
          const text = `${TITLES[t.id]} ${tileText(t)}${t.state === 'FRESH' ? '' : ', Datenstand veraltet'}`;
          if (previous.current[t.id] !== undefined && previous.current[t.id] !== text)
            changes.push(text);
          previous.current[t.id] = text;
        }
        if (changes.length > 0) announce(`Live-Werte geändert: ${changes.join('; ')}`);
      })
      .catch((e: unknown) => {
        setLoading(false);
        setFailed({ at: new Date().toISOString(), status: e instanceof ApiError ? e.status : 0 });
      });
  }, [api, announce]);

  const refresh = data?.refresh_seconds ?? 30;
  useEffect(() => {
    if (!canRead) return undefined;
    poll();
    const id = setInterval(poll, refresh * 1000);
    return () => clearInterval(id);
  }, [canRead, poll, refresh]);

  if (!context) return null;
  return (
    <ScreenFrame title="Live-Betrieb" context={context}>
      <p>
        Offene Anfragen, verfügbare Kapazität, kritische Verspätungen und Fahrzeugausfälle des
        aktiven Mandanten und Ihrer Dienste. Die Werte werden alle {refresh} Sekunden abgerufen.
      </p>
      {!canRead && (
        <p role="status">Für diese Sitzung ist das Live-Dashboard nicht freigeschaltet.</p>
      )}
      {canRead && loading && <p role="status">Live-Werte werden geladen …</p>}
      {failed && (
        <p role="alert">
          Aktualisierung fehlgeschlagen{failed.status ? ` (Status ${failed.status})` : ''}. Die
          angezeigten Werte sind die zuletzt bekannten und können veraltet sein.
        </p>
      )}
      {data && (
        <>
          <p>
            Abgerufen {data.evaluated_at} · ältester Datenstand {data.as_of ?? 'unbekannt'} ·
            Schwelle „kritisch“: {data.tolerance_min} min · lange Ausfälle ab {data.alert_min} min
          </p>
          <div>
            {data.tiles.map((t) => (
              <Kachel key={t.id} tile={t} stale={failed !== undefined} />
            ))}
          </div>
        </>
      )}
    </ScreenFrame>
  );
}

function Kachel({ tile, stale }: { tile: LiveTile; stale: boolean }) {
  const old = tile.state !== 'FRESH' || stale;
  const badge =
    tile.state === 'NO_DATA'
      ? { tone: 'info' as const, label: 'Kein Datenstand' }
      : old
        ? { tone: 'warning' as const, label: 'Veraltet' }
        : { tone: 'ok' as const, label: 'Aktuell' };
  return (
    <section aria-labelledby={`live-${tile.id}`} data-state={tile.state}>
      <h2 id={`live-${tile.id}`}>{TITLES[tile.id]}</h2>
      <p>{HINTS[tile.id]}</p>
      <p>
        <strong>{tileText(tile)}</strong> <StatusBadge tone={badge.tone}>{badge.label}</StatusBadge>
      </p>
      <p>
        {tile.as_of === null
          ? 'Datenstand: noch keine Ereignisse verarbeitet'
          : `Datenstand ${tile.as_of} (${formatAge(tile.age_seconds ?? 0)})`}
        {old && tile.value !== null ? ' – zuletzt bekannter Wert' : ''}
      </p>
    </section>
  );
}
