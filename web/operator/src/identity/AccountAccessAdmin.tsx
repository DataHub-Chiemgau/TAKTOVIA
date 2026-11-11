import { ApiError, useApi, useDraftState } from '@taktovia/api-client';
import type { SessionResource } from '@taktovia/api-client';
import { DataTable, Field, Form, StatusBadge, zeit } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { DEFAULT_ZONE } from '../ScreenContext';

interface LockedAccount {
  subject: string;
  revision: number;
}

const EMPTY_LOCK = { subject: '', reason: '' };

/**
 * UI-18 Basis (Nutzer sperren): Konto im aktiven Mandanten sperren und entsperren, Wiederherstellung über den Identitätsanbieter anstoßen,
 * Sitzungen des Mandanten sperren (WP-M1-10). Der Mandant steht nie in einer Anfrage. Entsperren braucht die Revision der Sperre (If-Match);
 * die Sperren dieser Seitensitzung werden hier gemerkt, eine Liste aller Sperren folgt mit der Auditabfrage (UI-20).
 */
export function AccountAccessAdmin() {
  const api = useApi();
  const [lock, setLock, lockDraft] = useDraftState(
    'account-lock',
    'Konto sperren',
    EMPTY_LOCK,
    (v) => v.subject !== '' || v.reason !== '',
  );
  const [recoverySubject, setRecoverySubject] = useState('');
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(
    lockDraft.restored ? 'Ein nicht gespeicherter Entwurf wurde wiederhergestellt.' : undefined,
  );
  const [locked, setLocked] = useState<LockedAccount[]>([]);
  const [sessions, setSessions] = useState<ResourceState<SessionResource[]>>({ status: 'loading' });
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => {
    setSessions({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listSessions', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setSessions({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setSessions({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const describe = (e: unknown, fallback: string): string => {
    if (e instanceof ApiError) {
      if (e.status === 404) return 'Das Konto gehört nicht zu diesem Mandanten.';
      if (e.status === 409)
        return 'Das ist im aktuellen Zustand nicht möglich (bereits gesperrt, eigenes Konto oder kein verifizierter Kontakt).';
      if (e.status === 412) return 'Der Stand hat sich geändert. Bitte neu laden.';
      if (e.status === 503)
        return 'Lokal umgesetzt; der Identitätsanbieter hat es noch nicht bestätigt. Bitte erneut versuchen.';
    }
    return fallback;
  };

  const doLock = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      const r = await api.call('lockAccount', {
        body: { subject: lock.subject.trim(), reason: lock.reason.trim() },
      });
      setLocked((old) => [
        ...old.filter((x) => x.subject !== r.subject),
        { subject: r.subject, revision: r.revision },
      ]);
      setLock(EMPTY_LOCK);
      setNotice(
        r.idp_synced === false
          ? 'Das Konto ist gesperrt; der Identitätsanbieter hat die Sitzungen noch nicht beendet.'
          : 'Das Konto wurde gesperrt.',
      );
      reload();
    } catch (e) {
      if (e instanceof ApiError && e.problem && e.status === 422) setProblem(e.problem);
      else setNotice(describe(e, 'Das Konto konnte nicht gesperrt werden.'));
    }
  };

  const unlock = async (a: LockedAccount) => {
    setNotice(undefined);
    try {
      await api.call('unlockAccount', { path: { subject: a.subject }, revision: a.revision });
      setLocked((old) => old.filter((x) => x.subject !== a.subject));
      setNotice(
        'Die Sperre wurde aufgehoben. Gesperrte Sitzungen bleiben gesperrt; das Konto meldet sich neu an.',
      );
    } catch (e) {
      setNotice(describe(e, 'Die Sperre konnte nicht aufgehoben werden.'));
    }
  };

  const recover = async () => {
    setNotice(undefined);
    try {
      await api.call('startAccountRecovery', { body: { subject: recoverySubject.trim() } });
      setRecoverySubject('');
      setNotice(
        'Die Wiederherstellung wurde angestoßen; alle bestehenden Sitzungen des Kontos sind beendet.',
      );
      reload();
    } catch (e) {
      setNotice(describe(e, 'Die Wiederherstellung konnte nicht angestoßen werden.'));
    }
  };

  const revokeSession = async (s: SessionResource) => {
    setNotice(undefined);
    try {
      await api.call('revokeSession', { path: { session_id: s.id }, revision: s.revision });
      setNotice('Die Sitzung wurde gesperrt.');
    } catch (e) {
      setNotice(describe(e, 'Die Sitzung konnte nicht gesperrt werden.'));
    }
    reload();
  };

  return (
    <section aria-labelledby="konten-h">
      <h2 id="konten-h">Konten und Sitzungen sperren</h2>
      {notice ? <p role="status">{notice}</p> : null}
      <h3>Konto sperren</h3>
      <Form onSubmit={doLock} submitLabel="Konto sperren" problem={problem}>
        <Field
          pointer="/subject"
          label="Konto (Subject)"
          value={lock.subject}
          onChange={(e) => setLock({ ...lock, subject: e.target.value })}
        />
        <Field
          pointer="/reason"
          label="Grund der Sperre"
          value={lock.reason}
          onChange={(e) => setLock({ ...lock, reason: e.target.value })}
        />
      </Form>
      {locked.length > 0 ? (
        <DataTable
          caption="In dieser Sitzung gesperrte Konten"
          rows={locked}
          rowKey={(a) => a.subject}
          columns={[
            { key: 's', header: 'Konto', render: (a) => a.subject },
            {
              key: 'a',
              header: 'Aktion',
              render: (a) => (
                <button type="button" onClick={() => void unlock(a)}>
                  Sperre aufheben ({a.subject})
                </button>
              ),
            },
          ]}
        />
      ) : null}
      <h3>Wiederherstellung anstoßen</h3>
      <p>
        Nur möglich, wenn der Identitätsanbieter einen verifizierten Kontakt führt; beendet alle
        Sitzungen des Kontos.
      </p>
      <Form onSubmit={recover} submitLabel="Wiederherstellung anstoßen">
        <Field
          pointer="/subject"
          label="Konto für Wiederherstellung"
          value={recoverySubject}
          onChange={(e) => setRecoverySubject(e.target.value)}
        />
      </Form>
      <h3>Sitzungen des Mandanten</h3>
      {sessions.status === 'loading' ? <p role="status">Sitzungen werden geladen …</p> : null}
      {sessions.status === 'error' ? (
        <p role="alert">
          Die Sitzungen konnten nicht geladen werden.{' '}
          <button type="button" onClick={reload}>
            Erneut versuchen
          </button>
        </p>
      ) : null}
      {sessions.status === 'ready' && sessions.data.length === 0 ? (
        <p>Keine Sitzungen im Mandanten.</p>
      ) : null}
      {sessions.status === 'ready' && sessions.data.length > 0 ? (
        <DataTable
          caption="Sitzungen im aktiven Mandanten"
          rows={sessions.data}
          rowKey={(s) => s.id}
          columns={[
            { key: 'sub', header: 'Konto', render: (s) => s.subject },
            {
              key: 'since',
              header: 'Angemeldet seit',
              render: (s) => zeit.toRfc3339(Date.parse(s.created_at), DEFAULT_ZONE),
            },
            {
              key: 'st',
              header: 'Status',
              render: (s) =>
                s.revoked_at ? (
                  <StatusBadge tone="neutral">gesperrt</StatusBadge>
                ) : (
                  <StatusBadge tone="ok">aktiv</StatusBadge>
                ),
            },
            {
              key: 'act',
              header: 'Aktion',
              render: (s) =>
                s.revoked_at ? null : (
                  <button type="button" onClick={() => void revokeSession(s)}>
                    Sitzung sperren ({s.subject})
                  </button>
                ),
            },
          ]}
        />
      ) : null}
    </section>
  );
}
