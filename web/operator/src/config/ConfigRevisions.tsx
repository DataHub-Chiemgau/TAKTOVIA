import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type {
  ConfigChange,
  ConfigParameter,
  ConfigParameterCatalog,
  ConfigRevision,
  ConfigScopeKind,
} from '@taktovia/api-client';
import { DataTable, ResourceView, ScreenFrame, StatusBadge, zeit } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import { DEFAULT_ZONE, useScreenContext } from '../ScreenContext';

const SCOPE_KINDS: readonly ConfigScopeKind[] = ['TENANT', 'REGION', 'DEPOT', 'SERVICE'];
const SCOPE_LABEL: Record<ConfigScopeKind, string> = {
  TENANT: 'Mandant',
  REGION: 'Region',
  DEPOT: 'Betriebshof',
  SERVICE: 'Dienst',
};
const STATE_LABEL: Record<ConfigRevision['state'], string> = {
  DRAFT: 'Entwurf',
  VALIDATED: 'geprüft',
  APPROVED: 'freigegeben',
  PUBLISHED: 'publiziert',
  SUPERSEDED: 'abgelöst',
};

const time = (iso: string) => zeit.toRfc3339(Date.parse(iso), DEFAULT_ZONE);
const show = (v: unknown) => (v === undefined ? '–' : JSON.stringify(v));

/** Diff Alt/Neu je Änderung: `/pfad: alt → neu` (hinzugefügt: kein Altwert, entfernt: kein Neuwert). */
function Diff({ changes }: { changes: ConfigChange[] }) {
  if (changes.length === 0) return <>keine Änderung</>;
  return (
    <ul>
      {changes.map((c) => (
        <li key={c.pointer}>
          <code>{c.pointer}</code>: {c.kind === 'ADDED' ? 'neu' : show(c.old_value)}
          {' → '}
          {c.kind === 'REMOVED' ? 'entfernt' : show(c.new_value)}
        </li>
      ))}
    </ul>
  );
}

function range(p: ConfigParameter): string {
  if (p.minimum !== null && p.maximum !== null) return `${p.minimum} bis ${p.maximum}`;
  if (p.type === 'boolean') return 'ja / nein';
  return '–';
}

/**
 * UI-17 Basis (Gebiete, Stops, Tarife, Konfiguration): Revisionsliste je Scope mit Ersteller, Zeitpunkt, Wirksamkeit, Grund und Diff Alt/Neu
 * (LH-I.7-CFG-003), Rekonstruktion „was galt zum Zeitpunkt T“ mit Revisions-ID (PH-AT-018) und der Parameterkatalog mit Einheit, Bereich und
 * Systemgrenze (LH-I.7-CFG-002/-004). Reine Anzeige: Anlegen, Prüfen und Freigeben folgen mit WP-M1-21 und -22. Der Mandant steht nie in einer Anfrage;
 * der Server bestimmt ihn aus der geprüften Mitgliedschaft. Rechte- und Fehlerzustand kommen aus dem Statuscode der Antwort, ohne Daten.
 */
export function ConfigRevisions() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Basis: Konfigurationsrevisionen' });
  const tenantId = session.status === 'ready' ? session.me.tenant_id : '';
  const [scopeKind, setScopeKind] = useState<ConfigScopeKind>('TENANT');
  const [scopeId, setScopeId] = useState(tenantId);
  const [applied, setApplied] = useState<{ kind: ConfigScopeKind; id: string }>({
    kind: 'TENANT',
    id: tenantId,
  });
  const [rows, setRows] = useState<ResourceState<ConfigRevision[]>>({ status: 'loading' });
  const [catalog, setCatalog] = useState<ResourceState<ConfigParameterCatalog>>({
    status: 'loading',
  });
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [asOfText, setAsOfText] = useState('');
  const [asOfResult, setAsOfResult] = useState<string | undefined>(undefined);
  const [asOfError, setAsOfError] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listConfigRevisions', {
        query: { limit: 50, scope_kind: applied.kind, scope_id: applied.id },
      })
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
  }, [api, applied, tick]);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listConfigParameters', {})
      .then((c) => {
        if (!cancelled) setCatalog({ status: 'ready', data: c });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setCatalog({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  /** Neu laden (Erneut versuchen): Zustand „lädt“ setzen, dann die Effekte erneut auslösen. */
  const reload = () => {
    setRows({ status: 'loading' });
    setCatalog({ status: 'loading' });
    setTick((n) => n + 1);
  };

  const loadMore = (cursor: string) => {
    setLoadingMore(true);
    api
      .call('listConfigRevisions', {
        query: { limit: 50, cursor, scope_kind: applied.kind, scope_id: applied.id },
      })
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

  const reconstruct = () => {
    setAsOfResult(undefined);
    setAsOfError(undefined);
    const ms = Date.parse(asOfText.trim());
    // Die Eingabe braucht einen Offset (RFC 3339); der Server weist sonst ab (OFFSET_REQUIRED).
    if (Number.isNaN(ms) || !/(Z|[+-]\d{2}:\d{2})$/.test(asOfText.trim())) {
      setAsOfError(
        'Bitte einen Zeitpunkt nach RFC 3339 mit Offset eingeben, z. B. 2026-03-15T08:00:00+01:00.',
      );
      return;
    }
    api
      .call('listConfigRevisions', {
        query: { scope_kind: applied.kind, scope_id: applied.id, as_of: asOfText.trim() },
      })
      .then((page) => {
        const r = page.items[0];
        setAsOfResult(
          r
            ? `Zum Zeitpunkt ${time(asOfText.trim())} wirkte Revision ${r.revision} (${r.id}), Grund: ${r.reason ?? '–'}.`
            : `Zum Zeitpunkt ${time(asOfText.trim())} wirkte keine publizierte Revision dieses Scopes.`,
        );
      })
      .catch(() => setAsOfError('Die wirksame Revision konnte nicht ermittelt werden.'));
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Gebiete, Stops, Tarife" context={context}>
      <h2>Konfigurationsrevisionen</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setRows({ status: 'loading' });
          setApplied({ kind: scopeKind, id: scopeId.trim() });
        }}
      >
        <label>
          Ebene
          <select
            value={scopeKind}
            onChange={(e) => {
              const k = e.target.value as ConfigScopeKind;
              setScopeKind(k);
              if (k === 'TENANT') setScopeId(tenantId);
            }}
          >
            {SCOPE_KINDS.map((k) => (
              <option key={k} value={k}>
                {SCOPE_LABEL[k]}
              </option>
            ))}
          </select>
        </label>{' '}
        <label>
          Kennung des Scopes
          <input
            type="text"
            value={scopeId}
            onChange={(e) => setScopeId(e.target.value)}
            readOnly={scopeKind === 'TENANT'}
          />
        </label>{' '}
        <button type="submit">Revisionen anzeigen</button>
      </form>
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Für diesen Scope gibt es noch keine Konfigurationsrevision',
          hint: 'Revisionen entstehen beim Anlegen eines Entwurfs.',
        }}
        errorAction={
          <button type="button" onClick={reload}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption={`Revisionen (${SCOPE_LABEL[applied.kind]})`}
            rows={data}
            rowKey={(r) => r.id}
            columns={[
              { key: 'rev', header: 'Revision', render: (r) => r.revision },
              {
                key: 'state',
                header: 'Status',
                render: (r) => (
                  <StatusBadge tone={r.state === 'PUBLISHED' ? 'ok' : 'neutral'}>
                    {STATE_LABEL[r.state]}
                  </StatusBadge>
                ),
              },
              { key: 'by', header: 'Ersteller', render: (r) => r.created_by },
              {
                key: 'at',
                header: 'Geändert am',
                render: (r) => (r.created_at ? time(r.created_at) : '–'),
              },
              {
                key: 'eff',
                header: 'Wirksam',
                render: (r) =>
                  `ab ${time(r.effective_from)}${r.effective_to ? `, bis ${time(r.effective_to)}` : ', unbefristet'}`,
              },
              { key: 'reason', header: 'Grund', render: (r) => r.reason ?? '–' },
              {
                key: 'diff',
                header: 'Änderungen (alt → neu)',
                render: (r) =>
                  r.change_log ? <Diff changes={r.change_log.changes} /> : <>nicht verfügbar</>,
              },
              { key: 'id', header: 'Revisions-ID', render: (r) => <code>{r.id}</code> },
            ]}
            page={{ nextCursor, loading: loadingMore, onLoadMore: loadMore }}
          />
        )}
      </ResourceView>
      <h2>Was galt zu einem Zeitpunkt?</h2>
      <label>
        Zeitpunkt (RFC 3339 mit Offset)
        <input type="text" value={asOfText} onChange={(e) => setAsOfText(e.target.value)} />
      </label>{' '}
      <button type="button" onClick={reconstruct}>
        Wirksame Revision anzeigen
      </button>
      {asOfResult ? <p role="status">{asOfResult}</p> : null}
      {asOfError ? <p role="alert">{asOfError}</p> : null}
      <h2>Parameterkatalog</h2>
      <ResourceView
        state={catalog}
        isEmpty={(c) => c.items.length === 0}
        empty={{
          title: 'Kein Parameterkatalog verfügbar',
          hint: 'Der Katalog wird per Migration bereitgestellt.',
        }}
      >
        {(c) => (
          <DataTable
            caption={`Parameterkatalog, Schema-Version ${c.schema_version} (nur lesbar; Änderung nur per Migration)`}
            rows={c.items}
            rowKey={(p) => p.name}
            columns={[
              { key: 'name', header: 'Name', render: (p) => <code>{p.name}</code> },
              { key: 'type', header: 'Datentyp', render: (p) => p.type },
              { key: 'unit', header: 'Einheit', render: (p) => p.unit },
              { key: 'range', header: 'Bereich (Systemgrenze)', render: range },
              { key: 'desc', header: 'Beschreibung', render: (p) => p.description },
              {
                key: 'safe',
                header: 'Sicherheit',
                render: (p) =>
                  p.security_class === 'SAFETY_INVARIANT' ? (
                    <StatusBadge tone="warning">nicht abschaltbar</StatusBadge>
                  ) : (
                    <>–</>
                  ),
              },
            ]}
          />
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
