import { ApiError, useApi } from '@taktovia/api-client';
import type {
  ConfigLevel,
  EffectiveConfig,
  EffectiveParameter,
  Region,
  Service,
} from '@taktovia/api-client';
import { DataTable, ResourceView, ScreenFrame, zeit } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import { DEFAULT_ZONE, useScreenContext } from '../ScreenContext';

/** Ebenen der Präzedenzkette in Klartext (LH-I.7-CFG-005); die Ebene Produkt folgt mit M2. */
export const LEVEL_LABEL: Readonly<Record<ConfigLevel, string>> = {
  GLOBAL: 'Global (Sicherheitsgrenze)',
  TENANT: 'Mandant',
  REGION: 'Region',
  SERVICE: 'Dienst',
  VEHICLE: 'Fahrzeug',
};
const OUTCOME_LABEL: Readonly<Record<string, string>> = {
  APPLIED: 'gilt',
  OVERRIDDEN: 'überstimmt',
  SKIPPED_LIMIT: 'verletzt die Sicherheitsgrenze, nicht wirksam',
  SKIPPED_NOT_ALLOWED: 'auf dieser Ebene nicht zulässig',
  NOT_SET: 'nicht gesetzt',
  NOT_IN_CONTEXT: 'nicht im Kontext',
};

const show = (v: unknown) => (v === null || v === undefined ? '–' : JSON.stringify(v));
const time = (iso: string | null) => (iso ? zeit.toRfc3339(Date.parse(iso), DEFAULT_ZONE) : '–');

/** Nächster vorgeschlagener (noch nicht publizierter) Wert: der der spezifischsten Ebene, die einen hat. */
function proposedOf(p: EffectiveParameter) {
  return p.precedence.find((e) => e.proposal !== null)?.proposal ?? null;
}

/**
 * UI-17 Basis, Konfigurationseditor (Kap. 06 §6.8, LH-I.7-CFG-005): je Parameter Name und Einheit, aktueller (wirksamer) Wert, vorgeschlagener Wert,
 * Herkunft der wirksamen Regel (Ebene, Revision), Vorrangregel und Wirksamkeitsdatum; bei Konflikt zwischen Mandant, Region und Dienst zeigt die Aufstellung
 * der Ebenen, welcher Wert gilt und welche überstimmt werden. Der Kontext (Region, Dienst) wählt das Gebiet; der Mandant steht nie in einer Anfrage.
 * Reine Anzeige: Ändern, Prüfen und Freigeben folgen mit WP-M1-22. Zustände: leer, Fehler (erneut laden), fehlendes Recht (403 ohne Daten).
 */
export function ConfigEditor() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Konfigurationseditor' });
  const [regions, setRegions] = useState<Region[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [regionId, setRegionId] = useState('');
  const [serviceId, setServiceId] = useState('');
  const [state, setState] = useState<ResourceState<EffectiveConfig>>({ status: 'loading' });
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listRegions', { query: { limit: 100 } })
      .then((p) => !cancelled && setRegions(p.items))
      .catch(() => !cancelled && setRegions([]));
    api
      .call('listServices', { query: { limit: 100 } })
      .then((p) => !cancelled && setServices(p.items))
      .catch(() => !cancelled && setServices([]));
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    let cancelled = false;
    const query: { region_id?: string; service_id?: string } = {};
    if (regionId) query.region_id = regionId;
    if (serviceId) query.service_id = serviceId;
    api
      .call('getEffectiveConfig', { query })
      .then((c) => !cancelled && setState({ status: 'ready', data: c }))
      .catch(
        (e: unknown) =>
          !cancelled &&
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
    return () => {
      cancelled = true;
    };
  }, [api, regionId, serviceId, tick]);

  if (!context) return null;
  const choose = (setter: (v: string) => void) => (v: string) => {
    setState({ status: 'loading' });
    setSelected(undefined);
    setter(v);
  };
  const visibleServices = regionId ? services.filter((s) => s.region_id === regionId) : services;

  return (
    <ScreenFrame title="Wirksame Konfiguration" context={context}>
      <p>
        <label>
          Region{' '}
          <select value={regionId} onChange={(e) => choose(setRegionId)(e.target.value)}>
            <option value="">Mandantenweit</option>
            {regions.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>{' '}
        <label>
          Dienst{' '}
          <select value={serviceId} onChange={(e) => choose(setServiceId)(e.target.value)}>
            <option value="">Kein Dienst</option>
            {visibleServices.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
      </p>
      <ResourceView
        state={state}
        isEmpty={(d) => (d.items ?? []).length === 0}
        empty={{ title: 'Keine Parameter im Katalog', hint: 'Der Parameterkatalog ist leer.' }}
        errorAction={
          <button
            type="button"
            onClick={() => {
              setState({ status: 'loading' });
              setTick((n) => n + 1);
            }}
          >
            Erneut versuchen
          </button>
        }
      >
        {(d) => (
          <>
            <DataTable
              caption="Wirksame Werte mit Herkunft und Vorrangregel"
              rows={d.items ?? []}
              rowKey={(p) => p.parameter}
              columns={[
                {
                  key: 'name',
                  header: 'Parameter',
                  render: (p) => (
                    <button type="button" onClick={() => setSelected(p.parameter)}>
                      {p.parameter}
                    </button>
                  ),
                },
                { key: 'unit', header: 'Einheit', render: (p) => p.unit },
                {
                  key: 'value',
                  header: 'Aktueller Wert',
                  render: (p) => (p.status === 'EFFECTIVE' ? show(p.value) : 'kein Wert'),
                },
                {
                  key: 'proposed',
                  header: 'Vorgeschlagener Wert',
                  render: (p) => {
                    const pr = proposedOf(p);
                    return pr ? `${show(pr.value)} (${pr.state})` : '–';
                  },
                },
                {
                  key: 'origin',
                  header: 'Herkunft',
                  render: (p) =>
                    p.level
                      ? `${LEVEL_LABEL[p.level]}${p.revision ? `, Revision ${p.revision}` : ''}`
                      : '–',
                },
                { key: 'rule', header: 'Vorrangregel', render: (p) => p.rule.description },
                { key: 'from', header: 'Wirksam ab', render: (p) => time(p.effective_from) },
              ]}
            />
            {selected ? <Precedence p={d.items?.find((p) => p.parameter === selected)} /> : null}
          </>
        )}
      </ResourceView>
    </ScreenFrame>
  );
}

/** Alle Ebenen eines Parameters (spezifischste zuerst): welcher Wert gilt, welche werden überstimmt oder verletzen die Sicherheitsgrenze. */
function Precedence({ p }: { p: EffectiveParameter | undefined }) {
  if (!p) return null;
  return (
    <section aria-label={`Ebenen von ${p.parameter}`}>
      <h2>Ebenen von {p.parameter}</h2>
      <DataTable
        caption={`Vorrang der Ebenen für ${p.parameter}`}
        rows={p.precedence}
        rowKey={(e) => e.level}
        columns={[
          { key: 'level', header: 'Ebene', render: (e) => LEVEL_LABEL[e.level] },
          { key: 'value', header: 'Wert', render: (e) => show(e.value) },
          {
            key: 'outcome',
            header: 'Ergebnis',
            render: (e) => OUTCOME_LABEL[e.outcome] ?? e.outcome,
          },
          {
            key: 'rev',
            header: 'Revision',
            render: (e) => (e.revision ? String(e.revision) : '–'),
          },
        ]}
      />
    </section>
  );
}
