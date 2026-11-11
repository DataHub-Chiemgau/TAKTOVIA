import { ApiError, useApi } from '@taktovia/api-client';
import type {
  GeoRelationCheck,
  GeoRelationRule,
  GeoServiceArea,
  GeoStop,
  Service,
  ServiceOperatingHours,
} from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StateView } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useScreenContext } from '../ScreenContext';

const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;
const WEEKDAY_LABEL: Record<string, string> = {
  MON: 'Mo',
  TUE: 'Di',
  WED: 'Mi',
  THU: 'Do',
  FRI: 'Fr',
  SAT: 'Sa',
  SUN: 'So',
};
const TRI = ['YES', 'NO', 'UNVERIFIED'] as const;
const TRI_LABEL: Record<string, string> = { YES: 'ja', NO: 'nein', UNVERIFIED: 'ungeprüft' };
const ROLE_LABEL: Record<string, string> = {
  BOTH: 'Start und Ziel',
  ORIGIN: 'nur Start',
  DESTINATION: 'nur Ziel',
};

type Notice = {
  tone: 'ok' | 'error';
  text: string;
  violations?: { pointer: string; message: string }[];
};

/** Meldung zu einem fehlgeschlagenen Aufruf; 412 heißt: eine andere Person hat die Ressource zwischenzeitlich geändert. */
function noticeFor(err: unknown, what: string): Notice {
  const status = err instanceof ApiError ? err.status : 0;
  if (status === 422)
    return {
      tone: 'error',
      text: `Die Eingabe wurde abgewiesen (422): ${what} wurde nicht gespeichert.`,
      violations: (err instanceof ApiError ? (err.problem?.violations ?? []) : []).map((v) => ({
        pointer: v.pointer,
        message: v.message,
      })),
    };
  if (status === 412 || status === 409)
    return {
      tone: 'error',
      text: 'Konkurrierende Version (412/409): die Daten wurden zwischenzeitlich geändert oder überschneiden sich. Bitte neu laden und erneut versuchen.',
    };
  if (status === 403) return { tone: 'error', text: 'Dafür fehlt das Recht (403).' };
  if (status === 404) return { tone: 'error', text: 'Nicht gefunden (404).' };
  return { tone: 'error', text: `${what} ist fehlgeschlagen. Bitte erneut versuchen.` };
}

function NoticeView({ notice }: { notice: Notice | undefined }) {
  if (!notice) return null;
  return (
    <div role={notice.tone === 'error' ? 'alert' : 'status'}>
      <p>{notice.text}</p>
      {notice.violations && notice.violations.length > 0 ? (
        <ul>
          {notice.violations.map((v) => (
            <li key={v.pointer + v.message}>
              <code>{v.pointer}</code>: {v.message}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** Lädt eine Ressource und liefert Zustand und Neuladen (Zustände: lädt, Fehler mit erneutem Laden, 403 ohne Daten). */
function useLoaded<T>(load: () => Promise<T>): [ResourceState<T>, () => void] {
  const [state, setState] = useState<ResourceState<T>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    load()
      .then((data) => {
        if (!cancelled) setState({ status: 'ready', data });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `load` ist je Aufrufer stabil über seine Eingaben; neu geladen wird über `tick`
  }, [tick]);
  const reload = useCallback(() => {
    setState({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);
  return [state, reload];
}

const retry = (reload: () => void) => (
  <button type="button" onClick={reload}>
    Erneut versuchen
  </button>
);

/** UI-17 (WP-M2-03): Betriebszeiten und Ausnahmetage eines Dienstes; jede Änderung ist eine neue Dienstversion (If-Match, 412). */
export function Betriebszeiten({ service }: { service: Service }) {
  const api = useApi();
  const [state, reload] = useLoaded(() =>
    api.call('getServiceOperatingHours', { path: { service_id: service.id } }),
  );
  const [days, setDays] = useState<string[]>(['MON', 'TUE', 'WED', 'THU', 'FRI']);
  const [from, setFrom] = useState('06:00');
  const [to, setTo] = useState('22:00');
  const [product, setProduct] = useState('AREA_ON_DEMAND');
  const [validFrom, setValidFrom] = useState('');
  const [exceptionDate, setExceptionDate] = useState('');
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);

  const save = (current: ServiceOperatingHours) => (e: FormEvent) => {
    e.preventDefault();
    setNotice(undefined);
    api
      .call('putServiceOperatingHours', {
        path: { service_id: service.id },
        revision: current.revision,
        body: {
          traffic_product: product.trim(),
          weekly_hours: [{ weekdays: days as never, from, to }],
          exceptions: exceptionDate ? [{ date: exceptionDate, windows: [] }] : [],
          valid_from: new Date(validFrom || Date.now()).toISOString(),
          valid_to: null,
          reason: reason.trim(),
        },
      })
      .then(() => {
        setNotice({ tone: 'ok', text: 'Betriebszeit wurde als neue Dienstversion gespeichert.' });
        reload();
      })
      .catch((err: unknown) => setNotice(noticeFor(err, 'Das Speichern der Betriebszeit')));
  };

  return (
    <section aria-labelledby={`bz-${service.id}`}>
      <h2 id={`bz-${service.id}`}>Betriebszeiten</h2>
      <ResourceView
        state={state}
        isEmpty={() => false}
        empty={{ title: '' }}
        errorAction={retry(reload)}
      >
        {(d) => (
          <>
            {d.revision === 0 ? (
              <StateView
                kind="empty"
                level={2}
                title="Keine Betriebszeit hinterlegt"
                hint="Der Dienst gilt als außer Betrieb und erzeugt keine Angebote."
              />
            ) : (
              <table aria-label="Wochenschema">
                <caption>
                  Version {d.revision}, Verkehrsprodukt {d.traffic_product}, Zeitzone {d.timezone}
                </caption>
                <thead>
                  <tr>
                    <th>Wochentage</th>
                    <th>Von</th>
                    <th>Bis</th>
                  </tr>
                </thead>
                <tbody>
                  {(d.weekly_hours ?? []).map((w, i) => (
                    <tr key={i}>
                      <td>{w.weekdays.map((x) => WEEKDAY_LABEL[x]).join(', ')}</td>
                      <td>{w.from}</td>
                      <td>{w.to}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <form onSubmit={save(d)} aria-label="Betriebszeit speichern">
              <fieldset>
                <legend>Wochentage</legend>
                {WEEKDAYS.map((w) => (
                  <label key={w}>
                    <input
                      type="checkbox"
                      checked={days.includes(w)}
                      onChange={(e) =>
                        setDays(e.target.checked ? [...days, w] : days.filter((x) => x !== w))
                      }
                    />{' '}
                    {WEEKDAY_LABEL[w]}{' '}
                  </label>
                ))}
              </fieldset>
              <label>
                Beginn (Ortszeit)
                <input type="time" value={from} onChange={(e) => setFrom(e.target.value)} />
              </label>{' '}
              <label>
                Ende (Ortszeit)
                <input type="time" value={to} onChange={(e) => setTo(e.target.value)} />
              </label>{' '}
              <label>
                Verkehrsprodukt
                <input value={product} onChange={(e) => setProduct(e.target.value)} required />
              </label>{' '}
              <label>
                Gültig ab
                <input
                  type="datetime-local"
                  value={validFrom}
                  onChange={(e) => setValidFrom(e.target.value)}
                />
              </label>{' '}
              <label>
                Ausnahmetag (geschlossen)
                <input
                  type="date"
                  value={exceptionDate}
                  onChange={(e) => setExceptionDate(e.target.value)}
                />
              </label>{' '}
              <label>
                Grund der Änderung
                <input value={reason} onChange={(e) => setReason(e.target.value)} required />
              </label>
              <button type="submit">Betriebszeit speichern</button>
            </form>
          </>
        )}
      </ResourceView>
      <NoticeView notice={notice} />
    </section>
  );
}

/** Koordinatenliste (Länge, Breite je Zeile) der Außenkontur des ersten Polygons: gleichwertige Textsicht neben der Karte. */
export function coordinateLines(area: GeoServiceArea['area']): string[] {
  const ring = area.coordinates[0]?.[0] ?? [];
  return ring.map((p) => `${p[0]}, ${p[1]}`);
}

function parseRing(text: string): number[][] {
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '')
    .map((l) => l.split(/[,;\s]+/).map(Number));
}

/** UI-17 (WP-M2-04, -06): Bediengebiete mit Zonenrolle; Koordinatenliste statt Kartenzeichnung (gleichwertige Alternative), Zustand „Ungültige Geometrie“ und „konkurrierende Version“. */
export function Bediengebiete({ service }: { service: Service }) {
  const api = useApi();
  const [state, reload] = useLoaded(() =>
    api.call('listServiceAreas', { query: { service_id: service.id } }),
  );
  const [selected, setSelected] = useState<GeoServiceArea | undefined>(undefined);
  const [coords, setCoords] = useState('');
  const [role, setRole] = useState<'BOTH' | 'ORIGIN' | 'DESTINATION'>('BOTH');
  const [validFrom, setValidFrom] = useState('');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);

  const save = (e: FormEvent) => {
    e.preventDefault();
    setNotice(undefined);
    const area = { type: 'MultiPolygon' as const, coordinates: [[parseRing(coords)]] };
    const from = new Date(validFrom || Date.now()).toISOString();
    const done = () => {
      setNotice({ tone: 'ok', text: 'Bediengebiet wurde gespeichert.' });
      setSelected(undefined);
      reload();
    };
    const call = selected
      ? api.call('updateServiceArea', {
          path: { area_id: selected.id },
          revision: selected.revision,
          body: { area, zone_role: role, valid_from: from, valid_to: null },
        })
      : api.call('createServiceArea', {
          body: { service_id: service.id, area, zone_role: role, valid_from: from, valid_to: null },
        });
    call.then(done).catch((err: unknown) => {
      const n = noticeFor(err, 'Das Speichern des Bediengebiets');
      setNotice(
        n.violations?.some((v) => v.pointer.startsWith('/area'))
          ? { ...n, text: `Ungültige Geometrie: ${n.text}` }
          : n,
      );
    });
  };

  return (
    <section aria-labelledby={`bg-${service.id}`}>
      <h2 id={`bg-${service.id}`}>Bediengebiete</h2>
      <ResourceView
        state={state}
        isEmpty={(d) => d.items.length === 0}
        empty={{ title: 'Noch kein Bediengebiet', hint: 'Legen Sie unten das erste Gebiet an.' }}
        errorAction={retry(reload)}
      >
        {(d) => (
          <ul>
            {d.items.map((a) => (
              <li key={a.id}>
                Revision {a.revision}, {ROLE_LABEL[a.zone_role]}, gültig ab {a.valid_from}
                {a.valid_to ? ` bis ${a.valid_to}` : ''}{' '}
                <button
                  type="button"
                  onClick={() => {
                    setSelected(a);
                    setCoords(coordinateLines(a.area).join('\n'));
                    setRole(a.zone_role);
                  }}
                >
                  Revision bearbeiten
                </button>
                <details>
                  <summary>Koordinatenliste</summary>
                  <pre>{coordinateLines(a.area).join('\n')}</pre>
                </details>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
      <form onSubmit={save} aria-label="Bediengebiet speichern">
        <label>
          Koordinaten der Außenkontur (Länge, Breite je Zeile; erste und letzte Zeile gleich)
          <textarea value={coords} onChange={(e) => setCoords(e.target.value)} rows={6} required />
        </label>{' '}
        <label>
          Zonenrolle
          <select value={role} onChange={(e) => setRole(e.target.value as typeof role)}>
            {Object.entries(ROLE_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </label>{' '}
        <label>
          Aktiv ab
          <input
            type="datetime-local"
            value={validFrom}
            onChange={(e) => setValidFrom(e.target.value)}
          />
        </label>
        <button type="submit">
          {selected ? 'Neue Revision speichern' : 'Bediengebiet anlegen'}
        </button>
      </form>
      <NoticeView notice={notice} />
    </section>
  );
}

/** UI-17 (WP-M2-05): Haltepunktliste und -formular (Aktivstatus, Verkehrsprodukte, Barrierefreiheit); Deaktivierung ist eine neue Revision. */
export function Haltepunkte() {
  const api = useApi();
  const [state, reload] = useLoaded(() => api.call('listStops', { query: { limit: 100 } }));
  const [name, setName] = useState('');
  const [lat, setLat] = useState('');
  const [lon, setLon] = useState('');
  const [products, setProducts] = useState('AREA_ON_DEMAND');
  const [wheelchair, setWheelchair] = useState<(typeof TRI)[number]>('UNVERIFIED');
  const [stepFree, setStepFree] = useState<(typeof TRI)[number]>('UNVERIFIED');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);

  const create = (e: FormEvent) => {
    e.preventDefault();
    setNotice(undefined);
    api
      .call('createStop', {
        body: {
          name: name.trim(),
          point: { latitude: Number(lat), longitude: Number(lon) },
          accessibility: {
            schema_version: '1.0',
            step_free: stepFree,
            wheelchair_boarding: wheelchair,
          },
          traffic_products: products
            .split(',')
            .map((p) => p.trim())
            .filter((p) => p !== ''),
          active: true,
          valid_from: new Date().toISOString(),
        },
      })
      .then(() => {
        setNotice({ tone: 'ok', text: 'Haltepunkt wurde angelegt.' });
        setName('');
        reload();
      })
      .catch((err: unknown) => setNotice(noticeFor(err, 'Das Anlegen des Haltepunkts')));
  };

  const deactivate = (s: GeoStop) => {
    setNotice(undefined);
    api
      .call('updateStop', {
        path: { stop_id: s.stop_id },
        revision: s.revision,
        body: {
          name: s.name,
          point: s.point,
          accessibility: s.accessibility,
          traffic_products: s.traffic_products,
          active: false,
          valid_from: new Date().toISOString(),
          valid_to: null,
        },
      })
      .then(() => {
        setNotice({ tone: 'ok', text: `Haltepunkt ${s.name} wurde deaktiviert (neue Revision).` });
        reload();
      })
      .catch((err: unknown) => setNotice(noticeFor(err, 'Das Deaktivieren')));
  };

  return (
    <section aria-labelledby="hp-h">
      <h2 id="hp-h">Haltepunkte</h2>
      <ResourceView
        state={state}
        isEmpty={(d) => d.items.length === 0}
        empty={{
          title: 'Noch keine Haltepunkte',
          hint: 'Legen Sie unten den ersten Haltepunkt an.',
        }}
        errorAction={retry(reload)}
      >
        {(d) => (
          <table aria-label="Haltepunkte">
            <thead>
              <tr>
                <th>Name</th>
                <th>Koordinate</th>
                <th>Verkehrsprodukte</th>
                <th>Rollstuhl</th>
                <th>Status</th>
                <th>Revision</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {d.items.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td>
                    {s.point.latitude}, {s.point.longitude}
                  </td>
                  <td>{s.traffic_products.join(', ')}</td>
                  <td>{TRI_LABEL[s.accessibility.wheelchair_boarding]}</td>
                  <td>{s.active ? 'aktiv' : 'inaktiv'}</td>
                  <td>{s.revision}</td>
                  <td>
                    {s.active ? (
                      <button type="button" onClick={() => deactivate(s)}>
                        {`Deaktivieren ${s.name}`}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ResourceView>
      <form onSubmit={create} aria-label="Haltepunkt anlegen">
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>{' '}
        <label>
          Breite
          <input
            value={lat}
            onChange={(e) => setLat(e.target.value)}
            required
            inputMode="decimal"
          />
        </label>{' '}
        <label>
          Länge
          <input
            value={lon}
            onChange={(e) => setLon(e.target.value)}
            required
            inputMode="decimal"
          />
        </label>{' '}
        <label>
          Verkehrsprodukte (kommagetrennt)
          <input value={products} onChange={(e) => setProducts(e.target.value)} required />
        </label>{' '}
        <label>
          Rollstuhl-Ein-/Ausstieg
          <select
            value={wheelchair}
            onChange={(e) => setWheelchair(e.target.value as typeof wheelchair)}
          >
            {TRI.map((t) => (
              <option key={t} value={t}>
                {TRI_LABEL[t]}
              </option>
            ))}
          </select>
        </label>{' '}
        <label>
          Stufenfrei
          <select value={stepFree} onChange={(e) => setStepFree(e.target.value as typeof stepFree)}>
            {TRI.map((t) => (
              <option key={t} value={t}>
                {TRI_LABEL[t]}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Haltepunkt anlegen</button>
      </form>
      <NoticeView notice={notice} />
    </section>
  );
}

/** UI-17 (WP-M2-06): erlaubte Zielpunkte eines Dienstes (Zonenrollen stehen an den Bediengebieten) und die Prüfung „Relation zulässig?“. */
export function Relationsregeln({ service }: { service: Service }) {
  const api = useApi();
  const [state, reload] = useLoaded(() =>
    Promise.all([
      api.call('getServiceRelationRule', { path: { service_id: service.id } }),
      api.call('listStops', { query: { limit: 100 } }),
    ]),
  );
  const [chosen, setChosen] = useState<string[] | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  const [probe, setProbe] = useState({ oLat: '', oLon: '', dLat: '', dLon: '' });
  const [result, setResult] = useState<GeoRelationCheck | undefined>(undefined);

  const save = (rule: GeoRelationRule, current: string[]) => (e: FormEvent) => {
    e.preventDefault();
    setNotice(undefined);
    api
      .call('putServiceRelationRule', {
        path: { service_id: service.id },
        revision: rule.revision,
        body: {
          destination_stop_ids: current,
          valid_from: new Date().toISOString(),
          valid_to: null,
          reason: reason.trim(),
        },
      })
      .then(() => {
        setNotice({ tone: 'ok', text: 'Erlaubte Zielpunkte wurden gespeichert.' });
        setChosen(undefined);
        reload();
      })
      .catch((err: unknown) => setNotice(noticeFor(err, 'Das Speichern der Zielpunkte')));
  };

  const check = (e: FormEvent) => {
    e.preventDefault();
    api
      .call('checkServiceRelation', {
        path: { service_id: service.id },
        query: {
          origin_latitude: Number(probe.oLat),
          origin_longitude: Number(probe.oLon),
          destination_latitude: Number(probe.dLat),
          destination_longitude: Number(probe.dLon),
        },
      })
      .then(setResult)
      .catch((err: unknown) => setNotice(noticeFor(err, 'Die Prüfung')));
  };

  return (
    <section aria-labelledby={`rr-${service.id}`}>
      <h2 id={`rr-${service.id}`}>Erlaubte Zielpunkte und Relationsprüfung</h2>
      <ResourceView
        state={state}
        isEmpty={() => false}
        empty={{ title: '' }}
        errorAction={retry(reload)}
      >
        {([rule, stops]) => {
          const current = chosen ?? rule.destination_stop_ids;
          return (
            <>
              {rule.destination_stop_ids.length === 0 ? (
                <p>Keine Einschränkung durch Zielpunkte (leere Menge).</p>
              ) : null}
              <form onSubmit={save(rule, current)} aria-label="Zielpunkte speichern">
                <fieldset>
                  <legend>Erlaubte Zielpunkte</legend>
                  {stops.items.map((s) => (
                    <label key={s.stop_id}>
                      <input
                        type="checkbox"
                        checked={current.includes(s.stop_id)}
                        onChange={(e) =>
                          setChosen(
                            e.target.checked
                              ? [...current, s.stop_id]
                              : current.filter((x) => x !== s.stop_id),
                          )
                        }
                      />{' '}
                      {s.name}
                      {s.active ? '' : ' (inaktiv)'}{' '}
                    </label>
                  ))}
                </fieldset>
                <label>
                  Grund der Änderung
                  <input value={reason} onChange={(e) => setReason(e.target.value)} required />
                </label>
                <button type="submit">Zielpunkte speichern</button>
              </form>
            </>
          );
        }}
      </ResourceView>
      <form onSubmit={check} aria-label="Relation prüfen">
        {(
          [
            ['oLat', 'Start Breite'],
            ['oLon', 'Start Länge'],
            ['dLat', 'Ziel Breite'],
            ['dLon', 'Ziel Länge'],
          ] as const
        ).map(([k, label]) => (
          <label key={k}>
            {label}
            <input
              value={probe[k]}
              onChange={(e) => setProbe({ ...probe, [k]: e.target.value })}
              required
              inputMode="decimal"
            />
          </label>
        ))}
        <button type="submit">Relation prüfen</button>
      </form>
      {result ? (
        <p role="status">
          {result.admissible
            ? 'Relation zulässig.'
            : `Relation abgelehnt: ${result.reason === 'OUTSIDE_AREA' ? 'außerhalb des Gebiets' : 'Regelverbot'}${result.detail ? ` (${result.detail})` : ''}.`}
        </p>
      ) : null}
      <NoticeView notice={notice} />
    </section>
  );
}

/** UI-17 „Gebiete, Haltepunkte, Betriebszeiten“: Dienstauswahl und die Bausteine der Geodatenpflege (WP-M2-03 bis -06). */
export function Geodaten() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Gebiete, Haltepunkte, Betriebszeiten' });
  const [state, reload] = useLoaded(() => api.call('listServices', { query: { limit: 100 } }));
  const [selectedId, setSelectedId] = useState('');
  if (!context) return null;
  return (
    <ScreenFrame title="Gebiete, Haltepunkte, Betriebszeiten" context={context}>
      <ResourceView
        state={state}
        isEmpty={(d) => d.items.length === 0}
        empty={{
          title: 'Noch keine Dienste',
          hint: 'Legen Sie zuerst einen Dienst in der Organisationsstruktur an.',
        }}
        errorAction={retry(reload)}
      >
        {(d) => {
          const service = d.items.find((s) => s.id === selectedId) ?? d.items[0];
          return (
            <>
              <label>
                Dienst
                <select value={service?.id ?? ''} onChange={(e) => setSelectedId(e.target.value)}>
                  {d.items.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
              {service ? (
                <div key={service.id}>
                  <Betriebszeiten service={service} />
                  <Bediengebiete service={service} />
                  <Relationsregeln service={service} />
                </div>
              ) : null}
              <Haltepunkte />
            </>
          );
        }}
      </ResourceView>
    </ScreenFrame>
  );
}
