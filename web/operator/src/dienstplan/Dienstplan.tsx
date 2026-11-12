import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type {
  ApiClient,
  AvailabilityRestriction,
  Depot,
  Driver,
  Roster,
  Shift,
} from '@taktovia/api-client';
import { ResourceView, ScreenFrame, ZonedTime } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DEFAULT_ZONE, useScreenContext } from '../ScreenContext';
import { DienstplanRaster } from './DienstplanRaster';
import { Veroeffentlichen, useSchichtBearbeitung } from './SchichtDragDrop';
import {
  konflikteJeSchicht,
  mondayOf,
  offeneZaehler,
  sideOf,
  verschiebeAnker,
  zeitraum,
} from './model';
import type { Ansicht, RosterBundle, Revisionssicht } from './model';
import { isoWoche } from './DienstplanRaster';

interface PlanData {
  bundles: RosterBundle[];
  drivers: Driver[];
  absences: AvailabilityRestriction[];
  fahrzeuge: Map<string, string>;
  asOf: string;
}

const MAX_PAGES = 20;

async function alleSeiten<T>(
  seite: (cursor: string | undefined) => Promise<{ items: T[]; next_cursor: string | null }>,
): Promise<T[]> {
  const out: T[] = [];
  let cursor: string | undefined;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page = await seite(cursor);
    out.push(...page.items);
    if (!page.next_cursor) break;
    cursor = page.next_cursor;
  }
  return out;
}

/** Lesemodell je Depot und Zeitraum: Dienstpläne (Wochen) mit Schichten, dazu Mitarbeiter, Abwesenheiten und Fahrzeugbezeichnungen (letztere best effort). */
async function ladeDaten(
  api: ApiClient,
  depotId: string,
  sicht: Revisionssicht,
  tage: string[],
  mondays: string[],
): Promise<PlanData> {
  const first = mondays[0] ?? mondayOf(tage[0] ?? '');
  const last = mondays[mondays.length - 1] ?? first;
  let asOf = '';
  const rosters = await alleSeiten<Roster>(async (cursor) => {
    const page = await api.call('listRosters', {
      query: {
        depot_id: depotId,
        from: first,
        to: last,
        limit: 100,
        ...(cursor ? { cursor } : {}),
      },
    });
    asOf = page.as_of;
    return page;
  });
  const bundles = await Promise.all(
    rosters
      .filter((r) => mondays.includes(r.week_start))
      .map(async (r): Promise<RosterBundle> => {
        const full = await api.call('getRoster', {
          path: { roster_id: r.id },
          query: { view: sicht },
        });
        const side = sideOf(full, sicht);
        const shifts: Shift[] = side
          ? await alleSeiten<Shift>((cursor) =>
              api.call('listRosterShifts', {
                query: { roster_id: r.id, view: sicht, limit: 200, ...(cursor ? { cursor } : {}) },
              }),
            )
          : [];
        return { roster: full, side, shifts };
      }),
  );
  bundles.sort((a, b) => a.roster.week_start.localeCompare(b.roster.week_start));
  const [drivers, absences, fahrzeuge] = await Promise.all([
    alleSeiten<Driver>((cursor) =>
      api.call('listDrivers', { query: { limit: 200, ...(cursor ? { cursor } : {}) } }),
    ).catch(() => [] as Driver[]),
    api
      .call('listAbsences', {
        query: { from: tage[0] ?? first, to: tage[tage.length - 1] ?? last, limit: 200 },
      })
      .then((p) => p.items)
      .catch(() => [] as AvailabilityRestriction[]),
    alleSeiten((cursor) =>
      api.call('listVehicleMasters', { query: { limit: 200, ...(cursor ? { cursor } : {}) } }),
    )
      .then(
        (items) =>
          new Map(
            items.map((v) => [v.id, v.vehicle_label ?? `Fahrzeug ${v.id.slice(0, 8)}`] as const),
          ),
      )
      .catch(() => new Map<string, string>()),
  ]);
  return { bundles, drivers, absences, fahrzeuge, asOf };
}

const todayIso = (): string => new Date().toISOString().slice(0, 10);

/**
 * UI-12 Dienstplan (WP-M4-18/-19, LH-6.23-SCH-001/-003): Planer-Dashboard mit Tages-, Wochen- (1 oder 2 Wochen) und Monatsansicht je Depot, Mitarbeitern als Zeilen,
 * Zeile der offenen Dienste mit Zähler, Konfliktmarken, Entwurf und veröffentlichter Revision getrennt sowie Datenstand; mit Schreibrecht Anlegen, Kopieren, Verschieben/Zuweisen
 * und Veröffentlichen ohne Seitenwechsel. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage (Depotrecht, Entwurfssicht, Konflikte).
 */
export function Dienstplan({ heute }: { heute?: string }) {
  const api = useApi();
  const session = useSession();
  const me = session.status === 'ready' ? session.me : undefined;
  const canWrite = me !== undefined && hasRight(me, 'roster.write');
  const canPublish = me !== undefined && hasRight(me, 'roster.publish');

  const [depots, setDepots] = useState<ResourceState<Depot[]>>({ status: 'loading' });
  const [depotId, setDepotId] = useState('');
  const [ansicht, setAnsicht] = useState<Ansicht>('woche');
  const [wochen, setWochen] = useState<1 | 2>(1);
  const [anker, setAnker] = useState(() => heute ?? todayIso());
  const [sicht, setSicht] = useState<Revisionssicht>('planner');
  const [loaded, setLoaded] = useState<{ key: string; state: ResourceState<PlanData> }>({
    key: '',
    state: { status: 'loading' },
  });
  const [tick, setTick] = useState(0);
  const [live, setLive] = useState('');
  const [anlegenFehler, setAnlegenFehler] = useState<string | undefined>(undefined);

  const zr = useMemo(() => zeitraum(ansicht, anker, wochen), [ansicht, anker, wochen]);
  // Anfrageschlüssel: ein Stand, der nicht zur aktuellen Auswahl gehört, gilt als „lädt“ (kein Zustand-Setzen im Effekt).
  const key = `${depotId}|${sicht}|${zr.days[0] ?? ''}|${zr.days.length}|${tick}`;
  const data = useMemo<ResourceState<PlanData>>(
    () => (loaded.key === key ? loaded.state : { status: 'loading' }),
    [loaded, key],
  );

  useEffect(() => {
    let cancelled = false;
    api
      .call('listDepots', { query: { limit: 200 } })
      .then((p) => {
        if (cancelled) return;
        setDepots({ status: 'ready', data: p.items });
        setDepotId((old) => old || (p.items[0]?.id ?? ''));
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setDepots({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const holen = useCallback(
    () => ladeDaten(api, depotId, sicht, zr.days, zr.mondays),
    [api, depotId, sicht, zr],
  );

  useEffect(() => {
    if (!depotId) return;
    let cancelled = false;
    holen()
      .then((d) => {
        if (!cancelled) setLoaded({ key, state: { status: 'ready', data: d } });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setLoaded({
            key,
            state: { status: 'error', error: e instanceof ApiError ? e : { status: 0 } },
          });
      });
    return () => {
      cancelled = true;
    };
  }, [holen, depotId, key]);

  /** Stilles Neuladen nach einer Änderung: kein Ladezustand, die Anzeige bleibt stehen (kein Seitenwechsel). */
  const reloadStill = useCallback(async () => {
    const d = await holen();
    setLoaded((old) => (old.key === key ? { key, state: { status: 'ready', data: d } } : old));
  }, [holen, key]);
  const patchShift = useCallback((s: Shift) => {
    setLoaded((old) =>
      old.state.status !== 'ready'
        ? old
        : {
            key: old.key,
            state: {
              status: 'ready',
              data: {
                ...old.state.data,
                bundles: old.state.data.bundles.map((b) =>
                  b.roster.id === s.roster_id
                    ? {
                        ...b,
                        shifts: b.shifts.some((x) => x.id === s.id)
                          ? b.shifts.map((x) => (x.id === s.id ? s : x))
                          : [...b.shifts, s],
                      }
                    : b,
                ),
              },
            },
          },
    );
  }, []);

  const bundles = useMemo(() => (data.status === 'ready' ? data.data.bundles : []), [data]);
  const drivers = data.status === 'ready' ? data.data.drivers : [];
  const absences = data.status === 'ready' ? data.data.absences : [];
  const fahrzeuge = data.status === 'ready' ? data.data.fahrzeuge : new Map<string, string>();

  const bearbeitung = useSchichtBearbeitung({
    bundles,
    drivers,
    absences,
    fahrzeuge,
    reload: reloadStill,
    patchShift,
    announce: setLive,
  });

  const konflikte = useMemo(
    () => konflikteJeSchicht(bundles, bearbeitung.extra),
    [bundles, bearbeitung.extra],
  );
  const imZeitraum = useMemo(
    () => bundles.flatMap((b) => b.shifts).filter((s) => zr.days.includes(s.date)),
    [bundles, zr],
  );
  const zaehler = offeneZaehler(imZeitraum);
  const anzahlKonflikte = imZeitraum.filter((s) => (konflikte.get(s.id)?.length ?? 0) > 0).length;
  const hatEntwurf = bundles.some((b) => b.side?.state === 'DRAFT');
  const hatVeroeffentlicht = bundles.some((b) => b.side?.state === 'PUBLISHED');
  const status =
    data.status !== 'ready'
      ? ({ tone: 'info', label: 'Dienstplan wird geladen' } as const)
      : hatEntwurf
        ? ({ tone: 'warning', label: 'Entwurf (nicht veröffentlicht)' } as const)
        : hatVeroeffentlicht
          ? ({ tone: 'ok', label: 'Veröffentlicht' } as const)
          : ({ tone: 'neutral', label: 'Kein Dienstplan im Zeitraum' } as const);
  const context = useScreenContext(status);
  if (!context) return null;

  const depot = depots.status === 'ready' ? depots.data.find((d) => d.id === depotId) : undefined;

  const legeWocheAn = async (monday: string) => {
    setAnlegenFehler(undefined);
    try {
      await api.call('createRoster', { body: { depot_id: depotId, week_start: monday } });
      setLive(`Dienstplan für die Woche ab ${monday} angelegt (Entwurf).`);
      setTick((n) => n + 1);
    } catch (e) {
      setAnlegenFehler(
        e instanceof ApiError && e.status === 403
          ? 'Ihnen fehlt das Recht, Dienstpläne anzulegen.'
          : 'Der Dienstplan konnte nicht angelegt werden.',
      );
    }
  };
  const oeffneEntwurf = async (b: RosterBundle) => {
    setAnlegenFehler(undefined);
    try {
      await api.call('openRosterDraft', {
        path: { roster_id: b.roster.id },
        revision: b.roster.revision,
      });
      setLive(`Entwurf für KW ${isoWoche(b.roster.week_start)} eröffnet.`);
      setTick((n) => n + 1);
    } catch (e) {
      setAnlegenFehler(
        e instanceof ApiError && e.status === 412
          ? 'Der Dienstplan wurde zwischenzeitlich geändert. Bitte neu laden.'
          : e instanceof ApiError && e.status === 409
            ? 'Es gibt bereits einen Entwurf. Bitte neu laden.'
            : 'Der Entwurf konnte nicht eröffnet werden.',
      );
    }
  };

  const retry = (
    <button type="button" onClick={() => setTick((n) => n + 1)}>
      Erneut versuchen
    </button>
  );

  return (
    <ScreenFrame title="Dienstplan" context={context}>
      <div className="dp-live tk-sr-only" role="status" aria-live="polite" aria-atomic="true">
        {live}
      </div>
      <ResourceView
        state={depots}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine Depots',
          hint: 'Für diesen Mandanten ist kein Depot angelegt oder für Sie freigegeben.',
        }}
        errorAction={retry}
      >
        {(list) => (
          <>
            <form
              className="dp-toolbar"
              aria-label="Zeitraum und Depot"
              onSubmit={(e) => e.preventDefault()}
            >
              <label>
                Depot
                <select value={depotId} onChange={(e) => setDepotId(e.target.value)}>
                  {list.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Ansicht
                <select value={ansicht} onChange={(e) => setAnsicht(e.target.value as Ansicht)}>
                  <option value="tag">Tag</option>
                  <option value="woche">Woche</option>
                  <option value="monat">Monat</option>
                </select>
              </label>
              {ansicht === 'woche' ? (
                <label>
                  Wochen
                  <select
                    value={wochen}
                    onChange={(e) => setWochen(e.target.value === '2' ? 2 : 1)}
                  >
                    <option value="1">1 Woche</option>
                    <option value="2">2 Wochen</option>
                  </select>
                </label>
              ) : null}
              <label>
                Revision
                <select value={sicht} onChange={(e) => setSicht(e.target.value as Revisionssicht)}>
                  <option value="planner">Entwurf (Arbeitsstand)</option>
                  <option value="published">Nur veröffentlicht</option>
                </select>
              </label>
              <span>
                <button
                  type="button"
                  onClick={() => setAnker(verschiebeAnker(ansicht, anker, -1, wochen))}
                >
                  Zurück
                </button>{' '}
                <button type="button" onClick={() => setAnker(heute ?? todayIso())}>
                  Heute
                </button>{' '}
                <button
                  type="button"
                  onClick={() => setAnker(verschiebeAnker(ansicht, anker, 1, wochen))}
                >
                  Weiter
                </button>
              </span>
            </form>
            <ResourceView
              state={data}
              isEmpty={(d) => d.bundles.length === 0}
              empty={{
                title: 'Kein Dienstplan im Zeitraum',
                hint: `Für ${depot?.name ?? 'dieses Depot'} gibt es im gewählten Zeitraum keinen Dienstplan.`,
                ...(canWrite && sicht === 'planner'
                  ? {
                      action: (
                        <button type="button" onClick={() => void legeWocheAn(mondayOf(anker))}>
                          Dienstplan für Woche ab {mondayOf(anker)} anlegen
                        </button>
                      ),
                    }
                  : {}),
              }}
              errorAction={retry}
            >
              {(d) => (
                <>
                  <dl className="tk-context-list" aria-label="Dienstplanstatus">
                    <div>
                      <dt>Depot</dt>
                      <dd>{depot?.name ?? '–'}</dd>
                    </div>
                    <div>
                      <dt>Revisionssicht</dt>
                      <dd>
                        {sicht === 'planner'
                          ? 'Entwurf (Arbeitsstand) mit veröffentlichter Revision als Grundlage'
                          : 'Nur veröffentlichte Revision'}
                      </dd>
                    </div>
                    <div>
                      <dt>Offene Dienste</dt>
                      <dd>
                        {zaehler.schichten} offen, {zaehler.bedarf} Personalbedarf ungedeckt
                      </dd>
                    </div>
                    <div>
                      <dt>Konflikte</dt>
                      <dd>{anzahlKonflikte} Schichten betroffen</dd>
                    </div>
                    <div>
                      <dt>Datenstand (Server)</dt>
                      <dd>
                        <ZonedTime instant={d.asOf} zone={DEFAULT_ZONE} />
                      </dd>
                    </div>
                  </dl>
                  {anlegenFehler ? <p role="alert">{anlegenFehler}</p> : null}
                  {canWrite && sicht === 'planner'
                    ? d.bundles
                        .filter((b) => b.side?.state === 'PUBLISHED')
                        .map((b) => (
                          <p key={b.roster.id}>
                            KW {isoWoche(b.roster.week_start)} ist veröffentlicht und nicht
                            änderbar.{' '}
                            <button type="button" onClick={() => void oeffneEntwurf(b)}>
                              Neuen Entwurf für KW {isoWoche(b.roster.week_start)} eröffnen
                            </button>
                          </p>
                        ))
                    : null}
                  {canWrite && sicht === 'planner'
                    ? zr.mondays
                        .filter((m) => !d.bundles.some((b) => b.roster.week_start === m))
                        .map((m) => (
                          <p key={m}>
                            Für KW {isoWoche(m)} gibt es keinen Dienstplan.{' '}
                            <button type="button" onClick={() => void legeWocheAn(m)}>
                              Dienstplan für KW {isoWoche(m)} anlegen
                            </button>
                          </p>
                        ))
                    : null}
                  <DienstplanRaster
                    ansicht={ansicht}
                    zeitraum={zr}
                    bundles={d.bundles}
                    drivers={d.drivers}
                    absences={d.absences}
                    fahrzeuge={d.fahrzeuge}
                    konflikte={konflikte}
                    meldungen={bearbeitung.meldungen}
                    speichernd={bearbeitung.speichernd}
                    aktionen={canWrite && sicht === 'planner' ? bearbeitung.aktionen : undefined}
                  />
                  {canPublish && sicht === 'planner' ? (
                    <Veroeffentlichen bundles={d.bundles} announce={setLive} onDone={reloadStill} />
                  ) : null}
                  {bearbeitung.ui}
                </>
              )}
            </ResourceView>
          </>
        )}
      </ResourceView>
      {depots.status === 'error' || data.status === 'error' ? null : (
        <p className="dp-hinweis">
          Tag, Woche oder Monat wählen; Schichten lassen sich bei Schreibrecht im Entwurf per Klick
          auf „+“ anlegen, kopieren, per Drag-and-drop oder mit „Verschieben/Zuweisen“ (Tastatur)
          einer Person zuordnen. Zeitzone: {DEFAULT_ZONE}.
        </p>
      )}
    </ScreenFrame>
  );
}
