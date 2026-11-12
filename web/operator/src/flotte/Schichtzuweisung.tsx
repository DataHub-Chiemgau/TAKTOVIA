import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { StaffShift } from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const toIso = (local: string): string => {
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? local : d.toISOString();
};

type Draft = {
  driver_id: string;
  vehicle_id: string;
  starts_at: string;
  ends_at: string;
  timezone: string;
  approved_by: string;
  reason: string;
};
const EMPTY: Draft = {
  driver_id: '',
  vehicle_id: '',
  starts_at: '',
  ends_at: '',
  timezone: 'Europe/Berlin',
  approved_by: '',
  reason: '',
};

/** Konfliktursache aus der Antwort: `/driver_id` = Fahrer, `/vehicle_id` = Fahrzeug; die kollidierende Schicht steht im Detail. */
export function conflictCause(p: ProblemLike | undefined): string | undefined {
  const pointer = p?.violations?.[0]?.pointer;
  if (pointer === '/driver_id') return 'Der Fahrer hat in diesem Zeitraum bereits eine Schicht.';
  if (pointer === '/vehicle_id')
    return 'Das Fahrzeug ist in diesem Zeitraum bereits einer anderen Schicht zugewiesen.';
  return undefined;
}

const STATES: Readonly<Record<string, string>> = {
  PLANNED: 'geplant',
  STARTED: 'angetreten',
  ENDED: 'beendet',
  CANCELLED: 'entzogen',
  PUBLISHED: 'veröffentlicht',
};

/**
 * UI-11 Flotte/Werkstatt: Zuweisungsdialog für Fahrer und Fahrzeug mit Konfliktanzeige (WP-M3-07, LH-6.8-FLT-003). Eine Überlappung (derselbe Fahrer oder dasselbe Fahrzeug) zeigt
 * die Ursache und die kollidierende Schicht; nur mit Genehmiger und Grund lässt sich die Doppelschicht dokumentiert zulassen (Planungsannahme PA-03). Die Dauer berechnet der Server
 * aus absoluten Zeitpunkten (Sommerzeitende). Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage. Die Einbindung in UI-07 folgt mit WP-M3-36.
 */
export function Schichtzuweisung() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Schichtzuweisung' });
  const canWrite = session.status === 'ready' && hasRight(session.me, 'shifts.write');
  const [rows, setRows] = useState<ResourceState<StaffShift[]>>({ status: 'loading' });
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [conflict, setConflict] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listStaffShifts', { query: { limit: 100 } })
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
    if (e.status === 403) return 'Ihnen fehlt das Recht, Schichten zuzuweisen.';
    if (e.status === 404) return 'Fahrer oder Fahrzeug wurde nicht gefunden.';
    if (e.status === 412) return 'Die Schicht wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const assign = async (withException: boolean) => {
    setProblem(undefined);
    setNotice(undefined);
    if (!withException) setConflict(undefined);
    try {
      await api.call('createStaffShift', {
        body: {
          driver_id: draft.driver_id.trim(),
          vehicle_id: draft.vehicle_id.trim(),
          starts_at: toIso(draft.starts_at),
          ends_at: toIso(draft.ends_at),
          timezone: draft.timezone.trim(),
          ...(withException
            ? {
                overlap_exception: {
                  approved_by: draft.approved_by.trim(),
                  reason: draft.reason.trim(),
                },
              }
            : {}),
        },
      });
      setDraft(EMPTY);
      setConflict(undefined);
      setNotice(
        withException
          ? 'Die Schicht wurde als dokumentierte Doppelschicht zugewiesen.'
          : 'Die Schicht wurde zugewiesen.',
      );
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.problem) setConflict(e.problem);
      else if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else setNotice(explain(e, 'Die Schicht konnte nicht zugewiesen werden.'));
    }
  };

  const withdraw = async (s: StaffShift) => {
    setNotice(undefined);
    try {
      await api.call('updateStaffShift', {
        path: { shift_id: s.id },
        body: {
          driver_id: s.driver_id,
          vehicle_id: s.vehicle_id,
          starts_at: s.starts_at,
          ends_at: s.ends_at,
          timezone: s.timezone,
          withdrawn: true,
        },
        revision: s.revision,
      });
      setNotice('Die Zuweisung wurde entzogen; Fahrer und Fahrzeug sind wieder frei.');
      load();
    } catch (e) {
      setNotice(explain(e, 'Die Zuweisung konnte nicht entzogen werden.'));
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Schichtzuweisung" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Schichten zugewiesen',
          hint: canWrite
            ? 'Weisen Sie unten die erste Schicht zu.'
            : 'Es wurden noch keine Schichten zugewiesen.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Schichtzuweisungen des aktiven Mandanten"
            rows={data}
            rowKey={(s) => s.id}
            columns={[
              { key: 'driver', header: 'Fahrer', render: (s) => s.driver_id },
              { key: 'vehicle', header: 'Fahrzeug', render: (s) => s.vehicle_id },
              {
                key: 'from',
                header: 'Zeitraum',
                render: (s) => `${s.starts_at} bis ${s.ends_at} (${s.duration_minutes} Minuten)`,
              },
              { key: 'state', header: 'Zustand', render: (s) => STATES[s.state] ?? s.state },
              {
                key: 'ex',
                header: 'Doppelschicht',
                render: (s) =>
                  s.overlap_exception
                    ? `genehmigt von ${s.overlap_exception.approved_by}: ${s.overlap_exception.reason}`
                    : 'nein',
              },
              {
                key: 'act',
                header: 'Aktion',
                render: (s) =>
                  canWrite && (s.state === 'PLANNED' || s.state === 'STARTED') ? (
                    <button type="button" onClick={() => void withdraw(s)}>
                      Zuweisung entziehen {s.id.slice(0, 8)}
                    </button>
                  ) : null,
              },
            ]}
          />
        )}
      </ResourceView>

      {canWrite ? (
        <section aria-labelledby="schicht-titel">
          <h2 id="schicht-titel">Schicht zuweisen</h2>
          {conflict ? (
            <div role="alert">
              <p>
                Konflikt: {conflictCause(conflict) ?? 'Die Schicht überlappt eine andere Schicht.'}
              </p>
              {conflict.detail ? <p>{conflict.detail}</p> : null}
              <p>
                Nur mit Genehmiger und Grund lässt sich eine Doppelschicht dokumentiert zulassen.
              </p>
            </div>
          ) : null}
          <Form onSubmit={() => assign(false)} submitLabel="Schicht zuweisen" problem={problem}>
            <Field
              pointer="/driver_id"
              label="Fahrer (Kennung)"
              value={draft.driver_id}
              onChange={(e) => setDraft({ ...draft, driver_id: e.target.value })}
            />
            <Field
              pointer="/vehicle_id"
              label="Fahrzeug (Kennung)"
              value={draft.vehicle_id}
              onChange={(e) => setDraft({ ...draft, vehicle_id: e.target.value })}
            />
            <Field
              pointer="/starts_at"
              label="Beginn (lokale Zeit)"
              type="datetime-local"
              value={draft.starts_at}
              onChange={(e) => setDraft({ ...draft, starts_at: e.target.value })}
            />
            <Field
              pointer="/ends_at"
              label="Ende (lokale Zeit)"
              type="datetime-local"
              value={draft.ends_at}
              onChange={(e) => setDraft({ ...draft, ends_at: e.target.value })}
            />
            <Field
              pointer="/timezone"
              label="Zeitzone (nur zur Anzeige)"
              value={draft.timezone}
              onChange={(e) => setDraft({ ...draft, timezone: e.target.value })}
            />
          </Form>
          {conflict ? (
            <section aria-labelledby="ausnahme-titel">
              <h3 id="ausnahme-titel">Dokumentierte Doppelschicht</h3>
              <Form
                onSubmit={() => assign(true)}
                submitLabel="Als Doppelschicht zuweisen"
                problem={undefined}
              >
                <Field
                  pointer="/overlap_exception/approved_by"
                  label="Genehmiger"
                  value={draft.approved_by}
                  onChange={(e) => setDraft({ ...draft, approved_by: e.target.value })}
                />
                <Field
                  pointer="/overlap_exception/reason"
                  label="Grund"
                  value={draft.reason}
                  onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
                />
              </Form>
            </section>
          ) : null}
        </section>
      ) : null}
    </ScreenFrame>
  );
}
