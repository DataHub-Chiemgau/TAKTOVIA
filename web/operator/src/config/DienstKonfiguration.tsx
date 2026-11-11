import { ApiError, useApi } from '@taktovia/api-client';
import type { ServiceOfferModelRules } from '@taktovia/api-client';
import { ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useScreenContext } from '../ScreenContext';

const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;
const WEEKDAY_LABEL: Record<(typeof WEEKDAYS)[number], string> = {
  MON: 'Mo',
  TUE: 'Di',
  WED: 'Mi',
  THU: 'Do',
  FRI: 'Fr',
  SAT: 'Sa',
  SUN: 'So',
};
/** Modellabhängige Parameter (WP-M1-25) mit Bezeichnung und Eingabeart; welche Pflicht, optional oder unzulässig sind, bestimmt die Regeltabelle des Servers. */
const PARAMS = [
  { name: 'max_detour_pct', label: 'Maximaler Umweg (%)', kind: 'number' },
  { name: 'stop_walk_max_m', label: 'Maximaler Fußweg zum Haltepunkt (m)', kind: 'number' },
  {
    name: 'advance_arrival_buffer_min',
    label: 'Ankunftspuffer vor Bahnabfahrt (min)',
    kind: 'number',
  },
  { name: 'pooling_allowed', label: 'Bündelung erlaubt', kind: 'boolean' },
] as const;

type Notice = {
  tone: 'ok' | 'error';
  text: string;
  violations?: { pointer: string; message: string }[];
};

/**
 * UI-17 Basis „Dienst anlegen“ (WP-M1-25, LH-6.1-SYS-003): Angebotsmodell wählen, modellabhängige Felder (Pflicht, optional; unzulässige Felder entfallen) ausfüllen und den Dienst
 * samt Entwurf der Dienstkonfiguration anlegen. Prüfen, Freigeben und Veröffentlichen übernimmt die Revisionsansicht (WP-M1-22). Die Modellregeln liefert der Server
 * (`listServiceOfferModels`, Planungsannahme PA-01, nicht fachlich freigegeben); der Server prüft sie bei Validierung und Publikation erneut. Zustände: lädt, Fehler, fehlendes Recht, 422 je Feld.
 */
export function DienstKonfiguration() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Dienstkonfiguration' });
  const [models, setModels] = useState<ResourceState<ServiceOfferModelRules[]>>({
    status: 'loading',
  });
  const [tick, setTick] = useState(0);
  const [model, setModel] = useState<ServiceOfferModelRules['model'] | ''>('');
  const [name, setName] = useState('');
  const [operatorId, setOperatorId] = useState('');
  const [timezone, setTimezone] = useState('Europe/Berlin');
  const [areaId, setAreaId] = useState('');
  const [tariffId, setTariffId] = useState('');
  const [days, setDays] = useState<string[]>(['MON', 'TUE', 'WED', 'THU', 'FRI']);
  const [from, setFrom] = useState('06:00');
  const [to, setTo] = useState('22:00');
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listServiceOfferModels', {})
      .then((r) => {
        if (!cancelled) setModels({ status: 'ready', data: r.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setModels({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const rules = models.status === 'ready' ? models.data.find((m) => m.model === model) : undefined;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!rules || !model) return;
    setBusy(true);
    setNotice(undefined);
    const extra: Record<string, unknown> = {};
    for (const p of PARAMS) {
      if (rules.forbidden.includes(p.name)) continue;
      const raw = values[p.name];
      if (raw === undefined || raw === '') continue;
      extra[p.name] = p.kind === 'boolean' ? raw === 'true' : Number(raw);
    }
    api
      .call('createService', {
        body: { operator_id: operatorId.trim(), name: name.trim(), timezone },
      })
      .then((service) =>
        api.call('updateConfigRevision', {
          path: { revision_id: service.config_revision_id },
          revision: 1,
          body: {
            values: {
              service_config: {
                service_area_id: areaId.trim(),
                traffic_type: model,
                operator_id: operatorId.trim(),
                operating_hours: [{ weekdays: days, from, to }],
                tariff_id: tariffId.trim(),
              },
              ...extra,
            },
            effective_from: new Date().toISOString(),
            reason: `Dienstkonfiguration ${rules.label}`,
          },
        }),
      )
      .then(() =>
        setNotice({
          tone: 'ok',
          text: 'Dienst und Entwurf der Dienstkonfiguration wurden angelegt. Prüfen und Freigeben folgen in der Revisionsansicht.',
        }),
      )
      .catch((err: unknown) => {
        const status = err instanceof ApiError ? err.status : 0;
        if (status === 422)
          setNotice({
            tone: 'error',
            text: 'Die Eingabe wurde abgewiesen (422).',
            violations: (err instanceof ApiError ? (err.problem?.violations ?? []) : []).map(
              (v) => ({
                pointer: v.pointer,
                message: v.message,
              }),
            ),
          });
        else if (status === 403) setNotice({ tone: 'error', text: 'Dafür fehlt das Recht (403).' });
        else if (status === 404)
          setNotice({ tone: 'error', text: 'Betreiber oder Bezug nicht gefunden (404).' });
        else
          setNotice({
            tone: 'error',
            text: 'Das Anlegen ist fehlgeschlagen. Bitte erneut versuchen.',
          });
      })
      .finally(() => setBusy(false));
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Dienst anlegen" context={context}>
      <ResourceView
        state={models}
        isEmpty={(d) => d.length === 0}
        empty={{ title: 'Keine Angebotsmodelle verfügbar' }}
        errorAction={
          <button type="button" onClick={() => setTick((n) => n + 1)}>
            Erneut versuchen
          </button>
        }
      >
        {(list) => (
          <form onSubmit={submit} aria-label="Dienst anlegen">
            <p>
              Modellregeln sind eine Planungsannahme (PA-01) und noch nicht fachlich freigegeben.
            </p>
            <label>
              Dienstname
              <input type="text" value={name} onChange={(e) => setName(e.target.value)} required />
            </label>{' '}
            <label>
              Betreiber (Kennung)
              <input
                type="text"
                value={operatorId}
                onChange={(e) => setOperatorId(e.target.value)}
                required
              />
            </label>{' '}
            <label>
              Zeitzone
              <input
                type="text"
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                required
              />
            </label>
            <label>
              Angebotsmodell
              <select
                value={model}
                onChange={(e) => setModel(e.target.value as typeof model)}
                required
              >
                <option value="">bitte wählen</option>
                {list.map((m) => (
                  <option key={m.model} value={m.model}>
                    {m.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Bediengebiet (Kennung)
              <input
                type="text"
                value={areaId}
                onChange={(e) => setAreaId(e.target.value)}
                required
              />
            </label>{' '}
            <label>
              Tarif (Kennung)
              <input
                type="text"
                value={tariffId}
                onChange={(e) => setTariffId(e.target.value)}
                required
              />
            </label>
            <fieldset>
              <legend>Betriebszeiten</legend>
              {WEEKDAYS.map((d) => (
                <label key={d}>
                  <input
                    type="checkbox"
                    checked={days.includes(d)}
                    onChange={(e) =>
                      setDays((old) =>
                        e.target.checked ? [...old, d] : old.filter((x) => x !== d),
                      )
                    }
                  />
                  {WEEKDAY_LABEL[d]}{' '}
                </label>
              ))}
              <label>
                Von
                <input type="text" value={from} onChange={(e) => setFrom(e.target.value)} />
              </label>{' '}
              <label>
                Bis
                <input type="text" value={to} onChange={(e) => setTo(e.target.value)} />
              </label>
            </fieldset>
            {rules ? (
              <fieldset>
                <legend>Modellabhängige Felder: {rules.label}</legend>
                {PARAMS.filter((p) => !rules.forbidden.includes(p.name)).map((p) => {
                  const required = rules.required.includes(p.name);
                  return (
                    <label key={p.name}>
                      {p.label}
                      {required ? ' (Pflicht)' : ' (optional)'}
                      {p.kind === 'boolean' ? (
                        <select
                          value={values[p.name] ?? ''}
                          required={required}
                          onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}
                        >
                          <option value="">nicht gesetzt</option>
                          <option value="true">ja</option>
                          <option value="false">nein</option>
                        </select>
                      ) : (
                        <input
                          type="number"
                          value={values[p.name] ?? ''}
                          required={required}
                          onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}
                        />
                      )}
                    </label>
                  );
                })}
                <p>
                  Für dieses Modell nicht vorgesehen:{' '}
                  {rules.forbidden.length === 0
                    ? 'keine Einschränkung'
                    : rules.forbidden.join(', ')}
                  .
                </p>
              </fieldset>
            ) : null}
            <button type="submit" disabled={busy || !rules}>
              Dienst anlegen
            </button>
          </form>
        )}
      </ResourceView>
      {notice ? (
        <div role={notice.tone === 'error' ? 'alert' : 'status'}>
          <p>{notice.text}</p>
          {notice.violations && notice.violations.length > 0 ? (
            <ul>
              {notice.violations.map((v) => (
                <li key={v.pointer}>
                  <code>{v.pointer}</code>: {v.message}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </ScreenFrame>
  );
}
