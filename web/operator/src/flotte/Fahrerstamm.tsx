import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type {
  ContactChannel,
  Driver,
  QualificationRevision,
  QualificationStatus,
} from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const CHANNELS: Readonly<Record<ContactChannel, string>> = {
  PHONE: 'Telefon',
  EMAIL: 'E-Mail',
  APP: 'App',
};
export const DECISIONS: Readonly<Record<QualificationStatus, string>> = {
  APPROVED: 'Freigeben',
  PENDING: 'Wartend anlegen',
  REVOKED: 'Entziehen',
};

const toIso = (local: string): string => {
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? local : d.toISOString();
};

type Draft = {
  membership_id: string;
  display_name: string;
  contact_channel: ContactChannel;
  contact_value: string;
};
const EMPTY: Draft = {
  membership_id: '',
  display_name: '',
  contact_channel: 'PHONE',
  contact_value: '',
};

type QualDraft = {
  qualification_code: string;
  valid_from: string;
  valid_to: string;
  evidence_ref: string;
  decision: QualificationStatus;
};
const EMPTY_QUAL: QualDraft = {
  qualification_code: '',
  valid_from: '',
  valid_to: '',
  evidence_ref: '',
  decision: 'APPROVED',
};

/**
 * UI-11 Flotte/Werkstatt: Fahrerliste und Qualifikationspflege (WP-M3-05, Grundform). HR-nahe Felder (Name, Kontakt, Nachweisverweis) liefert der Server nur der Personalplanung; die
 * Dispositionssicht zeigt stattdessen „nicht sichtbar“. Qualifikationen sind Revisionen: jede Änderung ist eine neue Zeile, alte bleiben lesbar; nur freigegebene, noch gültige
 * Qualifikationen gelten. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage.
 */
export function Fahrerstamm() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Fahrer und Qualifikationen' });
  const canWrite = session.status === 'ready' && hasRight(session.me, 'drivers.write');
  const [rows, setRows] = useState<ResourceState<Driver[]>>({ status: 'loading' });
  const [selected, setSelected] = useState<Driver | undefined>(undefined);
  const [quals, setQuals] = useState<QualificationRevision[] | undefined>(undefined);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [qualDraft, setQualDraft] = useState<QualDraft>(EMPTY_QUAL);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [qualProblem, setQualProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listDrivers', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht, Fahrer zu verwalten.';
    if (e.status === 404) return 'Der Fahrer wurde nicht gefunden.';
    if (e.status === 409)
      return 'Konflikt: Diese Mitgliedschaft ist bereits einem Fahrer zugeordnet.';
    if (e.status === 412) return 'Der Fahrer wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const loadQualifications = (d: Driver) => {
    setQuals(undefined);
    api
      .call('listDriverQualifications', { path: { driver_id: d.id } })
      .then((page) => setQuals(page.items))
      .catch((e: unknown) =>
        setNotice(explain(e, 'Die Qualifikationen konnten nicht geladen werden.')),
      );
  };

  const select = (d: Driver) => {
    setSelected(d);
    setQualProblem(undefined);
    setQualDraft(EMPTY_QUAL);
    loadQualifications(d);
  };

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createDriver', {
        body: {
          membership_id: draft.membership_id.trim(),
          display_name: draft.display_name.trim(),
          contact_channel: draft.contact_channel,
          contact_value: draft.contact_value.trim(),
        },
      });
      setDraft(EMPTY);
      setNotice('Der Fahrer wurde angelegt und mit der Mitgliedschaft verknüpft.');
      load();
    } catch (e) {
      if (e instanceof ApiError && (e.status === 422 || e.status === 409) && e.problem)
        setProblem(e.problem);
      else setNotice(explain(e, 'Der Fahrer konnte nicht angelegt werden.'));
    }
  };

  const toggleActive = async (d: Driver) => {
    setNotice(undefined);
    try {
      const updated = await api.call('updateDriver', {
        path: { driver_id: d.id },
        body: {
          display_name: d.display_name ?? '',
          contact_channel: d.contact_channel ?? 'APP',
          contact_value: d.contact_value ?? '',
          active: d.status !== 'ACTIVE',
        },
        revision: d.revision,
      });
      setNotice(
        updated.status === 'ACTIVE' ? 'Der Fahrer ist aktiv.' : 'Der Fahrer wurde deaktiviert.',
      );
      if (selected?.id === d.id) setSelected(updated);
      load();
    } catch (e) {
      setNotice(explain(e, 'Der Fahrer konnte nicht geändert werden.'));
    }
  };

  const addQualification = async () => {
    if (!selected) return;
    setQualProblem(undefined);
    setNotice(undefined);
    try {
      const created = await api.call('createDriverQualification', {
        path: { driver_id: selected.id },
        body: {
          qualification_code: qualDraft.qualification_code.trim(),
          valid_from: toIso(qualDraft.valid_from),
          valid_to: qualDraft.valid_to ? toIso(qualDraft.valid_to) : null,
          evidence_ref: qualDraft.evidence_ref.trim() === '' ? null : qualDraft.evidence_ref.trim(),
          decision: qualDraft.decision,
        },
        revision: selected.revision,
      });
      setNotice(
        'Die Qualifikationsrevision wurde angelegt; die bisherigen Revisionen bleiben lesbar.',
      );
      const fresh = await api.call('getDriver', { path: { driver_id: selected.id } });
      setSelected({ ...fresh, revision: created.driver_revision ?? fresh.revision });
      setQualDraft(EMPTY_QUAL);
      loadQualifications(fresh);
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setQualProblem(e.problem);
      else setNotice(explain(e, 'Die Qualifikation konnte nicht gespeichert werden.'));
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Fahrer und Qualifikationen" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Fahrer in diesem Mandanten',
          hint: canWrite
            ? 'Legen Sie unten den ersten Fahrer an.'
            : 'Es wurden noch keine Fahrer angelegt.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Fahrer des aktiven Mandanten"
            rows={data}
            rowKey={(d) => d.id}
            columns={[
              {
                key: 'name',
                header: 'Name',
                render: (d) => (d.hr_fields_visible ? (d.display_name ?? '') : 'nicht sichtbar'),
              },
              {
                key: 'status',
                header: 'Status',
                render: (d) => (d.status === 'ACTIVE' ? 'aktiv' : 'deaktiviert'),
              },
              {
                key: 'seq',
                header: 'Qualifikationsnummer',
                render: (d) => String(d.qualification_seq),
              },
              {
                key: 'act',
                header: 'Aktion',
                render: (d) => (
                  <>
                    <button type="button" onClick={() => select(d)}>
                      Qualifikationen{' '}
                      {d.hr_fields_visible ? (d.display_name ?? d.id) : d.id.slice(0, 8)}
                    </button>
                    {canWrite ? (
                      <button type="button" onClick={() => void toggleActive(d)}>
                        {d.status === 'ACTIVE' ? 'Deaktivieren' : 'Aktivieren'}{' '}
                        {d.hr_fields_visible ? (d.display_name ?? d.id) : d.id.slice(0, 8)}
                      </button>
                    ) : null}
                  </>
                ),
              },
            ]}
          />
        )}
      </ResourceView>

      {selected ? (
        <section aria-labelledby="qual-titel">
          <h2 id="qual-titel">Qualifikationsrevisionen</h2>
          {quals ? (
            quals.length === 0 ? (
              <p>Für diesen Fahrer wurden noch keine Qualifikationen erfasst.</p>
            ) : (
              <DataTable
                caption="Qualifikationsrevisionen des gewählten Fahrers"
                rows={quals}
                rowKey={(q) => q.id}
                columns={[
                  { key: 'code', header: 'Qualifikation', render: (q) => q.qualification_code },
                  { key: 'rev', header: 'Revision', render: (q) => String(q.revision) },
                  { key: 'st', header: 'Freigabe', render: (q) => q.status },
                  {
                    key: 'iv',
                    header: 'Gültig',
                    render: (q) => `${q.valid_from} bis ${q.valid_to ?? 'unbegrenzt'}`,
                  },
                  {
                    key: 'now',
                    header: 'Gilt jetzt',
                    render: (q) => (q.valid_at ? 'ja' : 'nein'),
                  },
                ]}
              />
            )
          ) : null}
          {canWrite ? (
            <Form onSubmit={addQualification} submitLabel="Revision anlegen" problem={qualProblem}>
              <Field
                pointer="/qualification_code"
                label="Qualifikation (Code)"
                value={qualDraft.qualification_code}
                onChange={(e) => setQualDraft({ ...qualDraft, qualification_code: e.target.value })}
              />
              <Field
                pointer="/valid_from"
                label="Gültig ab (lokale Zeit)"
                type="datetime-local"
                value={qualDraft.valid_from}
                onChange={(e) => setQualDraft({ ...qualDraft, valid_from: e.target.value })}
              />
              <Field
                pointer="/valid_to"
                label="Gültig bis (lokale Zeit, leer = unbegrenzt)"
                type="datetime-local"
                value={qualDraft.valid_to}
                onChange={(e) => setQualDraft({ ...qualDraft, valid_to: e.target.value })}
              />
              <Field
                pointer="/evidence_ref"
                label="Nachweisverweis (nur für die Personalplanung sichtbar)"
                value={qualDraft.evidence_ref}
                onChange={(e) => setQualDraft({ ...qualDraft, evidence_ref: e.target.value })}
              />
              <div className="tk-field">
                <label htmlFor="decision">Entscheidung</label>
                <select
                  id="decision"
                  value={qualDraft.decision}
                  onChange={(e) =>
                    setQualDraft({ ...qualDraft, decision: e.target.value as QualificationStatus })
                  }
                >
                  {(Object.keys(DECISIONS) as QualificationStatus[]).map((k) => (
                    <option key={k} value={k}>
                      {DECISIONS[k]}
                    </option>
                  ))}
                </select>
              </div>
            </Form>
          ) : null}
        </section>
      ) : null}

      {canWrite ? (
        <section aria-labelledby="fahrer-titel">
          <h2 id="fahrer-titel">Fahrer anlegen</h2>
          <Form onSubmit={create} submitLabel="Fahrer anlegen" problem={problem}>
            <Field
              pointer="/membership_id"
              label="Mitgliedschaft (Kennung, Rolle Fahrer)"
              value={draft.membership_id}
              onChange={(e) => setDraft({ ...draft, membership_id: e.target.value })}
            />
            <Field
              pointer="/display_name"
              label="Name"
              value={draft.display_name}
              onChange={(e) => setDraft({ ...draft, display_name: e.target.value })}
            />
            <div className="tk-field">
              <label htmlFor="contact-channel">Kontaktkanal</label>
              <select
                id="contact-channel"
                value={draft.contact_channel}
                onChange={(e) =>
                  setDraft({ ...draft, contact_channel: e.target.value as ContactChannel })
                }
              >
                {(Object.keys(CHANNELS) as ContactChannel[]).map((k) => (
                  <option key={k} value={k}>
                    {CHANNELS[k]}
                  </option>
                ))}
              </select>
            </div>
            <Field
              pointer="/contact_value"
              label="Kontakt"
              value={draft.contact_value}
              onChange={(e) => setDraft({ ...draft, contact_value: e.target.value })}
            />
          </Form>
        </section>
      ) : null}
    </ScreenFrame>
  );
}
