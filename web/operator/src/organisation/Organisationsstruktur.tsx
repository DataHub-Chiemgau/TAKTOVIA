import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { CostCenter, Depot, Region, Service } from '@taktovia/api-client';
import { Form, Field, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

interface Structure {
  regions: Region[];
  depots: Depot[];
  services: Service[];
  costCenters: CostCenter[];
}

/** Auswahl im Strukturbaum: bestimmt die Kontextzeile „Mandant/Region/Dienst“ (Kap. 06 §6.1). */
interface Selection {
  region?: Region;
  service?: Service;
}

/**
 * UI-17 Basis: Strukturbaum Mandant → Region → Dienst mit den Betriebshöfen und Kostenstellen des Gebiets (LH-I.4-MUL-002). Der Mandant steht nie in einer
 * Anfrage; Daten und Rechte bestimmt der Server aus der geprüften Mitgliedschaft. Zustände: leer, Fehler (mit erneutem Laden), fehlendes Recht (403 ohne Daten).
 * Anlegen von Regionen ist in dieser Basis möglich; Betriebshöfe, Dienste und Kostenstellen legt die Oberfläche mit M2 an (die API steht).
 */
export function Organisationsstruktur() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Organisationsstruktur' });
  const tenantName = session.status === 'ready' ? session.me.tenant_name : '';
  const [state, setState] = useState<ResourceState<Structure>>({ status: 'loading' });
  const [selection, setSelection] = useState<Selection>({});
  const [name, setName] = useState('');
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => {
    setState({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const query = { query: { limit: 100 } };
    Promise.all([
      api.call('listRegions', query),
      api.call('listDepots', query),
      api.call('listServices', query),
      api.call('listCostCenters', query),
    ])
      .then(([regions, depots, services, costCenters]) => {
        if (!cancelled)
          setState({
            status: 'ready',
            data: {
              regions: regions.items,
              depots: depots.items,
              services: services.items,
              costCenters: costCenters.items,
            },
          });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const createRegion = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createRegion', { body: { name: name.trim() } });
      setName('');
      setNotice('Region wurde angelegt.');
      reload();
    } catch (e) {
      if (e instanceof ApiError && e.problem) setProblem(e.problem);
      else setNotice('Die Region konnte nicht angelegt werden.');
    }
  };

  if (!context) return null;
  const contextLine = [tenantName, selection.region?.name, selection.service?.name]
    .filter((p): p is string => Boolean(p))
    .join(' / ');

  return (
    <ScreenFrame title="Organisationsstruktur" context={context}>
      <p aria-label="Mandant, Region, Dienst">{contextLine}</p>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={state}
        isEmpty={(d) => d.regions.length === 0 && d.services.length === 0}
        empty={{
          title: 'Noch keine Regionen oder Dienste in diesem Mandanten',
          hint: 'Legen Sie unten die erste Region an.',
        }}
        errorAction={
          <button type="button" onClick={reload}>
            Erneut versuchen
          </button>
        }
      >
        {(d) => <Tree data={d} tenant={tenantName} selection={selection} onSelect={setSelection} />}
      </ResourceView>
      <h2>Region anlegen</h2>
      <Form onSubmit={createRegion} submitLabel="Anlegen" problem={problem}>
        <Field
          pointer="/name"
          label="Name der Region"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </Form>
    </ScreenFrame>
  );
}

function Tree(props: {
  data: Structure;
  tenant: string;
  selection: Selection;
  onSelect: (s: Selection) => void;
}) {
  const { data, tenant, selection, onSelect } = props;
  const unassigned = data.services.filter((s) => !s.region_id);
  const servicesOf = (r: Region) => data.services.filter((s) => s.region_id === r.id);
  const costCenters = (match: (c: CostCenter) => boolean) => data.costCenters.filter(match);
  return (
    <nav aria-label="Strukturbaum">
      <ul>
        <li>
          <strong>{tenant}</strong>
          <ul>
            {data.regions.map((r) => (
              <li key={r.id}>
                <button
                  type="button"
                  aria-pressed={selection.region?.id === r.id && !selection.service}
                  onClick={() => onSelect({ region: r })}
                >
                  Region {r.name}
                </button>
                <ul>
                  {servicesOf(r).map((s) => (
                    <li key={s.id}>
                      <button
                        type="button"
                        aria-pressed={selection.service?.id === s.id}
                        onClick={() => onSelect({ region: r, service: s })}
                      >
                        Dienst {s.name}
                      </button>
                      {costCenters((c) => c.service_id === s.id).map((c) => (
                        <span key={c.id}> · Kostenstelle {c.code}</span>
                      ))}
                    </li>
                  ))}
                  {data.depots
                    .filter((dp) => dp.region_id === r.id)
                    .map((dp) => (
                      <li key={dp.id}>Betriebshof {dp.name}</li>
                    ))}
                  {costCenters((c) => c.region_id === r.id && !c.service_id).map((c) => (
                    <li key={c.id}>
                      Kostenstelle {c.code} {c.name}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
            {unassigned.length > 0 ? (
              <li>
                Ohne Region
                <ul>
                  {unassigned.map((s) => (
                    <li key={s.id}>
                      <button
                        type="button"
                        aria-pressed={selection.service?.id === s.id}
                        onClick={() => onSelect({ service: s })}
                      >
                        Dienst {s.name}
                      </button>
                    </li>
                  ))}
                </ul>
              </li>
            ) : null}
          </ul>
        </li>
      </ul>
    </nav>
  );
}
