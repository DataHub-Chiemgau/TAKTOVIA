import { ApiError, useApi } from '@taktovia/api-client';
import type { LegalEntity, Organization, OrganizationRole } from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

/** Vertragliche Rollen einer Organisation (LH-I.4-MUL-003); getrennte Werte, keine wird aus einer anderen abgeleitet. */
export const ROLE_LABELS: Readonly<Record<OrganizationRole, string>> = {
  PLATFORM_OPERATOR: 'Plattformbetreiber',
  TRANSPORT_COMPANY: 'Verkehrsunternehmen',
  TASK_AUTHORITY: 'Aufgabenträger',
  CLIENT: 'Auftraggeber',
  BROKER: 'Vermittler',
  ACTUAL_CARRIER: 'Tatsächlicher Beförderer',
};
const ROLES = Object.keys(ROLE_LABELS) as OrganizationRole[];

/**
 * UI-18 Basis: Liste und Formular Organisationen mit Rechtsträgerrollen im **aktiven Mandanten**. Der Mandant steht nie in einer Anfrage.
 * Zustände: fehlendes Recht (403 aus der Antwort, ohne Daten), „Scopekonflikt“ (409 doppelte federation_ref, 412 zwischenzeitlich
 * geändert) und Feldfehler (422, z. B. unbekannte Rolle).
 */
export function OrganizationAdmin() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Organisationen' });
  const [rows, setRows] = useState<ResourceState<Organization[]>>({ status: 'loading' });
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [form, setForm] = useState<{
    name: string;
    federation_ref: string;
    legal_entity_id: string;
    roles: OrganizationRole[];
  }>({ name: '', federation_ref: '', legal_entity_id: '', roles: [] });
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listOrganizations', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    api
      .call('listLegalEntities', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setLegalEntities(page.items);
      })
      .catch(() => {
        if (!cancelled) setLegalEntities([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const toggle = (role: OrganizationRole) =>
    setForm((f) => ({
      ...f,
      roles: f.roles.includes(role) ? f.roles.filter((r) => r !== role) : [...f.roles, role],
    }));

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createOrganization', {
        body: {
          name: form.name.trim(),
          federation_ref: form.federation_ref.trim(),
          legal_entity_id: form.legal_entity_id,
          roles: form.roles,
        },
      });
      setForm({ name: '', federation_ref: '', legal_entity_id: '', roles: [] });
      setNotice('Organisation wurde angelegt.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409)
        setNotice('Scopekonflikt: Diese federation_ref ist in Ihrem Mandanten bereits vergeben.');
      else if (e instanceof ApiError && e.problem) setProblem(e.problem);
      else setNotice('Die Organisation konnte nicht angelegt werden.');
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Organisationen und Rechtsträgerrollen" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Organisationen in diesem Mandanten',
          hint: 'Legen Sie unten die erste Organisation an.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Organisationen des aktiven Mandanten"
            rows={data}
            rowKey={(o) => o.id}
            columns={[
              { key: 'name', header: 'Name', render: (o) => o.name },
              { key: 'ref', header: 'Föderationskennung', render: (o) => o.federation_ref },
              {
                key: 'roles',
                header: 'Rollen',
                render: (o) => o.roles.map((r) => ROLE_LABELS[r]).join(', '),
              },
            ]}
          />
        )}
      </ResourceView>
      <h2>Organisation anlegen</h2>
      <Form onSubmit={create} submitLabel="Anlegen" problem={problem}>
        <Field
          pointer="/name"
          label="Name"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <Field
          pointer="/federation_ref"
          label="Föderationskennung"
          value={form.federation_ref}
          onChange={(e) => setForm({ ...form, federation_ref: e.target.value })}
        />
        <div className="tk-field">
          <label htmlFor="legal-entity">Rechtsträger</label>
          <select
            id="legal-entity"
            value={form.legal_entity_id}
            onChange={(e) => setForm({ ...form, legal_entity_id: e.target.value })}
          >
            <option value="">Bitte wählen</option>
            {legalEntities.map((le) => (
              <option key={le.id} value={le.id}>
                {le.name}
              </option>
            ))}
          </select>
        </div>
        <fieldset>
          <legend>Vertragliche Rollen</legend>
          {ROLES.map((role) => (
            <label key={role} style={{ display: 'block' }}>
              <input
                type="checkbox"
                checked={form.roles.includes(role)}
                onChange={() => toggle(role)}
              />{' '}
              {ROLE_LABELS[role]}
            </label>
          ))}
        </fieldset>
      </Form>
    </ScreenFrame>
  );
}
