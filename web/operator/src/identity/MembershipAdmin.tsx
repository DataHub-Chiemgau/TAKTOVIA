import { ApiError, useApi } from '@taktovia/api-client';
import type { Membership } from '@taktovia/api-client';
import {
  DataTable,
  Field,
  Form,
  ResourceView,
  ScreenFrame,
  StatusBadge,
  zeit,
} from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { DEFAULT_ZONE, useScreenContext } from '../ScreenContext';
import { useSession } from '@taktovia/api-client';

/**
 * UI-18 Basis (Kooperation und Rollen): Mitgliedschaften und Rollen **des aktiven Mandanten** verwalten. Der Mandant steht nie in einer
 * Anfrage; der Server bestimmt ihn aus der geprüften Mitgliedschaft, und diese Liste enthält nur dessen Zeilen. Rechte-Zustand (403) und
 * Fehlerzustand kommen aus dem Statuscode der Antwort, ohne Daten. Kooperationsgrants folgen mit M6 (Zustände Vertragsablauf, offene
 * Reisen, Scopekonflikt gehören dorthin).
 */
export function MembershipAdmin() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Mitgliedschaften' });
  const [rows, setRows] = useState<ResourceState<Membership[]>>({ status: 'loading' });
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [form, setForm] = useState({ subject: '', role_code: '' });

  const [tick, setTick] = useState(0);
  /** Neu laden (aus Ereignissen): Zustand „lädt“ setzen, dann den Effekt erneut auslösen. */
  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listMemberships', { query: { limit: 50 } })
      .then((page) => {
        if (cancelled) return;
        setRows({ status: 'ready', data: page.items });
        setNextCursor(page.next_cursor);
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const loadMore = (cursor: string) => {
    setLoadingMore(true);
    api
      .call('listMemberships', { query: { limit: 50, cursor } })
      .then((page) => {
        setRows((old) =>
          old.status === 'ready' ? { status: 'ready', data: [...old.data, ...page.items] } : old,
        );
        setNextCursor(page.next_cursor);
      })
      .catch((e: unknown) =>
        setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      )
      .finally(() => setLoadingMore(false));
  };

  const revoke = async (m: Membership) => {
    setNotice(undefined);
    try {
      await api.call('revokeMembership', {
        path: { membership_id: m.id },
        revision: m.revision,
      });
      setNotice(`Mitgliedschaft ${m.role_code} wurde entzogen.`);
      load();
    } catch (e) {
      // 412: jemand hat die Mitgliedschaft zwischenzeitlich geändert; neu laden statt überschreiben
      setNotice(
        e instanceof ApiError && e.status === 412
          ? 'Die Mitgliedschaft wurde zwischenzeitlich geändert. Die Liste wurde neu geladen.'
          : 'Die Mitgliedschaft konnte nicht entzogen werden.',
      );
      load();
    }
  };

  const grant = async () => {
    setProblem(undefined);
    setNotice(undefined);
    if (session.status !== 'ready') return;
    try {
      await api.call('createMembership', {
        // Issuer des angemeldeten Kontos; der Mandant steht nicht im Body, er folgt aus dem Kontext des Servers.
        body: {
          issuer: session.me.issuer,
          subject: form.subject.trim(),
          role_code: form.role_code.trim(),
        },
      });
      setForm({ subject: '', role_code: '' });
      setNotice('Mitgliedschaft wurde erteilt.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.problem) setProblem(e.problem);
      else setNotice('Die Mitgliedschaft konnte nicht erteilt werden.');
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Kooperation und Rollen" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Mitgliedschaften in diesem Mandanten',
          hint: 'Erteilen Sie unten die erste Mitgliedschaft.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Mitgliedschaften des aktiven Mandanten"
            rows={data}
            rowKey={(m) => m.id}
            columns={[
              { key: 'subject', header: 'Konto', render: (m) => m.subject },
              { key: 'role', header: 'Rolle', render: (m) => m.role_code },
              {
                key: 'scope',
                header: 'Dienste',
                render: (m) =>
                  m.service_scope === null
                    ? 'keine Freigabe'
                    : `${m.service_scope.length} Dienst(e)`,
              },
              {
                key: 'valid',
                header: 'Gültig bis',
                render: (m) =>
                  m.valid_to ? zeit.toRfc3339(Date.parse(m.valid_to), DEFAULT_ZONE) : 'unbefristet',
              },
              {
                key: 'state',
                header: 'Status',
                render: (m) =>
                  m.revoked_at ? (
                    <StatusBadge tone="neutral">entzogen</StatusBadge>
                  ) : (
                    <StatusBadge tone="ok">aktiv</StatusBadge>
                  ),
              },
              {
                key: 'action',
                header: 'Aktion',
                render: (m) =>
                  m.revoked_at ? null : (
                    <button type="button" onClick={() => void revoke(m)}>
                      Entziehen ({m.subject}, {m.role_code})
                    </button>
                  ),
              },
            ]}
            page={{ nextCursor, loading: loadingMore, onLoadMore: loadMore }}
          />
        )}
      </ResourceView>
      <h2>Mitgliedschaft erteilen</h2>
      <Form onSubmit={grant} submitLabel="Erteilen" problem={problem}>
        <Field
          pointer="/subject"
          label="Konto (Subject)"
          value={form.subject}
          onChange={(e) => setForm({ ...form, subject: e.target.value })}
        />
        <Field
          pointer="/role_code"
          label="Rollencode"
          value={form.role_code}
          onChange={(e) => setForm({ ...form, role_code: e.target.value })}
        />
      </Form>
    </ScreenFrame>
  );
}
