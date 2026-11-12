import { ApiError, useApi } from '@taktovia/api-client';
import type { Service, TariffRevision, TariffRuleInput } from '@taktovia/api-client';
import { ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useScreenContext } from '../ScreenContext';

const STATE_LABEL: Record<string, string> = {
  ENTWURF: 'Entwurf',
  GEPRUEFT: 'geprüft',
  VEROEFFENTLICHT: 'veröffentlicht',
};
const TYPE_LABEL: Record<string, string> = {
  FESTPREIS: 'Festpreis',
  ZONENPREIS: 'Zonenpreis',
  GRUNDPREIS_STRECKE: 'Grundpreis plus Strecke',
};

type Notice = {
  tone: 'ok' | 'error';
  text: string;
  violations?: { pointer: string; message: string }[];
};

/** Meldung zu einem fehlgeschlagenen Aufruf; 412/409 heißt: konkurrierende Version oder Überlappung; 403 bei Prüfung/Freigabe: Vier-Augen-Regel oder fehlendes Recht. */
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
  if (status === 412)
    return {
      tone: 'error',
      text: 'Konkurrierende Version (412): die Revision wurde zwischenzeitlich geändert. Bitte neu laden und erneut versuchen.',
    };
  if (status === 409)
    return {
      tone: 'error',
      text: 'Konflikt (409): der Gültigkeitszeitraum überschneidet sich mit einer veröffentlichten Revision, oder die Revision ist nicht mehr im erwarteten Zustand. Bitte neu laden.',
    };
  if (status === 403)
    return {
      tone: 'error',
      text: 'Nicht erlaubt (403): Prüfung und Freigabe erfordern eine zweite Person; der Ersteller kann nicht selbst freigeben.',
    };
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

/** Ganzzahl in der kleinsten Einheit aus der Eingabe (Betrag), `NaN` bei Unsinn; der Server prüft erneut (422). */
function toMinor(text: string): number {
  return /^[0-9]+$/.test(text.trim()) ? Number(text.trim()) : Number.NaN;
}

/** Preisregel aus den Formularfeldern (Regeltyp bestimmt, welche Felder gesendet werden). */
export function buildRule(f: {
  type: TariffRuleInput['rule_type'];
  amount: string;
  rate: string;
  zoneFrom: string;
  zoneTo: string;
  taxRate: string;
  taxRevision: string;
}): TariffRuleInput {
  const rule: TariffRuleInput = {
    rule_type: f.type,
    amount_minor: toMinor(f.amount),
    tax_rate: f.taxRate.trim() === '' ? '0' : f.taxRate.trim(),
    tax_rule_revision: f.taxRevision.trim(),
  };
  if (f.type === 'ZONENPREIS') {
    rule.zone_from = f.zoneFrom.trim();
    rule.zone_to = f.zoneTo.trim();
  }
  if (f.type === 'GRUNDPREIS_STRECKE') rule.rate_per_km = f.rate.trim();
  return rule;
}

/** UI-17 (WP-M2-07): Tarifrevisionen eines Dienstes anlegen, prüfen und freigeben; Vier-Augen und der Zustand „konkurrierende Version“. */
export function Tarifrevisionen({ service }: { service: Service }) {
  const api = useApi();
  const [state, reload] = useLoaded(() =>
    api.call('listTariffRevisions', { query: { service_id: service.id } }),
  );
  const [tariffClass, setTariffClass] = useState('STANDARD');
  const [validFrom, setValidFrom] = useState('');
  const [validTo, setValidTo] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [reason, setReason] = useState('');
  const [type, setType] = useState<TariffRuleInput['rule_type']>('FESTPREIS');
  const [amount, setAmount] = useState('');
  const [rate, setRate] = useState('');
  const [zoneFrom, setZoneFrom] = useState('');
  const [zoneTo, setZoneTo] = useState('');
  const [taxRate, setTaxRate] = useState('0.07');
  const [taxRevision, setTaxRevision] = useState('');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);

  const create = (e: FormEvent) => {
    e.preventDefault();
    setNotice(undefined);
    api
      .call('createTariffRevision', {
        body: {
          service_id: service.id,
          tariff_class: tariffClass.trim(),
          valid_from: new Date(validFrom || Date.now()).toISOString(),
          valid_to: validTo ? new Date(validTo).toISOString() : null,
          currency: currency.trim(),
          reason: reason.trim(),
          rules: [buildRule({ type, amount, rate, zoneFrom, zoneTo, taxRate, taxRevision })],
        },
      })
      .then(() => {
        setNotice({ tone: 'ok', text: 'Tarifrevision wurde als Entwurf angelegt.' });
        reload();
      })
      .catch((err: unknown) => setNotice(noticeFor(err, 'Das Anlegen der Tarifrevision')));
  };

  const step = (r: TariffRevision, op: 'reviewTariffRevision' | 'publishTariffRevision') => {
    setNotice(undefined);
    api
      .call(op, { path: { tariff_id: r.id }, revision: r.revision })
      .then(() => {
        setNotice({
          tone: 'ok',
          text:
            op === 'reviewTariffRevision'
              ? 'Tarifrevision wurde geprüft.'
              : 'Tarifrevision wurde veröffentlicht und ist jetzt unveränderlich.',
        });
        reload();
      })
      .catch((err: unknown) =>
        setNotice(
          noticeFor(err, op === 'reviewTariffRevision' ? 'Die Prüfung' : 'Die Veröffentlichung'),
        ),
      );
  };

  return (
    <section aria-labelledby={`tr-${service.id}`}>
      <h2 id={`tr-${service.id}`}>Tarifrevisionen</h2>
      <ResourceView
        state={state}
        isEmpty={(d) => d.items.length === 0}
        empty={{
          title: 'Noch keine Tarifrevision',
          hint: 'Ohne veröffentlichte Revision gibt es „kein Tarif“ und keine Angebote.',
        }}
        errorAction={retry(reload)}
      >
        {(d) => (
          <table aria-label="Tarifrevisionen">
            <thead>
              <tr>
                <th>Nr.</th>
                <th>Tarifklasse</th>
                <th>Zustand</th>
                <th>Gültig ab (einschließlich)</th>
                <th>Gültig bis (ausschließlich)</th>
                <th>Regeltyp</th>
                <th>Aktion</th>
              </tr>
            </thead>
            <tbody>
              {d.items.map((r) => (
                <tr key={r.id}>
                  <td>{r.revision_no}</td>
                  <td>{r.tariff_class}</td>
                  <td>{STATE_LABEL[r.state]}</td>
                  <td>{r.valid_from}</td>
                  <td>{r.valid_to ?? 'offen'}</td>
                  <td>{r.rules[0] ? TYPE_LABEL[r.rules[0].rule_type] : '–'}</td>
                  <td>
                    {r.state === 'ENTWURF' ? (
                      <button type="button" onClick={() => step(r, 'reviewTariffRevision')}>
                        Revision {r.revision_no} prüfen
                      </button>
                    ) : null}
                    {r.state === 'GEPRUEFT' ? (
                      <button type="button" onClick={() => step(r, 'publishTariffRevision')}>
                        Revision {r.revision_no} freigeben
                      </button>
                    ) : null}
                    {r.state === 'VEROEFFENTLICHT' ? 'unveränderlich' : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ResourceView>
      <form onSubmit={create} aria-label="Tarifrevision anlegen">
        <label>
          Tarifklasse
          <input value={tariffClass} onChange={(e) => setTariffClass(e.target.value)} required />
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
          Gültig bis (leer = offen)
          <input
            type="datetime-local"
            value={validTo}
            onChange={(e) => setValidTo(e.target.value)}
          />
        </label>{' '}
        <label>
          Währung
          <input value={currency} onChange={(e) => setCurrency(e.target.value)} required />
        </label>{' '}
        <label>
          Regeltyp
          <select
            value={type}
            onChange={(e) => setType(e.target.value as TariffRuleInput['rule_type'])}
          >
            {Object.entries(TYPE_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </label>{' '}
        <label>
          Betrag (kleinste Einheit)
          <input
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="numeric"
            required
          />
        </label>{' '}
        {type === 'ZONENPREIS' ? (
          <>
            <label>
              Startzone (Kennung)
              <input value={zoneFrom} onChange={(e) => setZoneFrom(e.target.value)} required />
            </label>{' '}
            <label>
              Zielzone (Kennung)
              <input value={zoneTo} onChange={(e) => setZoneTo(e.target.value)} required />
            </label>{' '}
          </>
        ) : null}
        {type === 'GRUNDPREIS_STRECKE' ? (
          <label>
            Streckensatz je km (Dezimalzahl)
            <input
              value={rate}
              onChange={(e) => setRate(e.target.value)}
              inputMode="decimal"
              required
            />
          </label>
        ) : null}{' '}
        <label>
          Steuersatz (z. B. 0.07)
          <input value={taxRate} onChange={(e) => setTaxRate(e.target.value)} inputMode="decimal" />
        </label>{' '}
        <label>
          Steuerregelrevision
          <input value={taxRevision} onChange={(e) => setTaxRevision(e.target.value)} required />
        </label>{' '}
        <label>
          Grund
          <input value={reason} onChange={(e) => setReason(e.target.value)} required />
        </label>
        <button type="submit">Tarifrevision anlegen</button>
      </form>
      <NoticeView notice={notice} />
    </section>
  );
}

/** UI-17 „Tarife“: Dienstauswahl und die Pflege der Tarifrevisionen (WP-M2-07). */
export function Tarife() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Tarife' });
  const [state, reload] = useLoaded(() => api.call('listServices', { query: { limit: 100 } }));
  const [selectedId, setSelectedId] = useState('');
  if (!context) return null;
  return (
    <ScreenFrame title="Tarife" context={context}>
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
              {service ? <Tarifrevisionen key={service.id} service={service} /> : null}
            </>
          );
        }}
      </ResourceView>
    </ScreenFrame>
  );
}
