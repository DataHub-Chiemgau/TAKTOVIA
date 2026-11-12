import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { Expense, ExpenseSummary } from '@taktovia/api-client';
import { ConflictDialog, ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { DEFAULT_ZONE, useScreenContext } from '../ScreenContext';
import { zeit } from '@taktovia/design-system';
import { Aufteilung } from './Aufteilung';
import { BelegFreigabe } from './BelegFreigabe';
import { BelegUpload } from './BelegUpload';
import { Gegenbeleg } from './Gegenbeleg';
import { OcrFelder } from './OcrFelder';
import { Original } from './Original';
import { Pruefhinweise } from './Pruefhinweise';
import { formatMinor } from './betrag';

export const STATUS_FILTER: ReadonlyArray<{ value: string; label: string }> = [
  { value: '', label: 'Alle' },
  { value: 'STORED', label: 'In Erfassung' },
  { value: 'IN_REVIEW', label: 'In fachlicher Prüfung' },
  { value: 'REVIEWED', label: 'Geprüft, wartet auf Freigabe' },
  { value: 'WAITING_SECOND_APPROVAL', label: 'Wartet auf zweite Freigabe' },
  { value: 'POSTED', label: 'Freigegeben und gebucht' },
  { value: 'QUARANTINED', label: 'In Quarantäne' },
];
const STATUS_LABEL = Object.fromEntries(STATUS_FILTER.map((f) => [f.value, f.label]));

type Detail =
  { status: 'loading' } | { status: 'error'; error: ApiError } | { status: 'ready'; data: Expense };

/**
 * UI-14 Belege/OCR: Prüfen und Freigeben (WP-M4-38; Kap. 06 §6.6/§6.9). Links die **Arbeitsliste nach Status** (leer, Ladefehler und fehlendes Recht sind eigene Zustände; Datenstand, Rechtsträger und Belegstatus
 * stehen im Kontextbalken), daneben der gewählte Beleg: **links das unveränderte Original, rechts** die Felder mit Konfidenz und Korrekturhistorie, Kostenzuordnung, Steuergruppe, Dublettenverknüpfung,
 * Plausibilitätshinweise, **Aufteilung mit Summenkontrolle** und die **Freigabe** (nur bei vollständiger Validierung, der Grund der Sperre wird genannt; im Vier-Augen-Fall steht, wer nicht freigeben darf).
 * Ein gebuchter Beleg bietet „Gegenbeleg erstellen“. Ein 412 öffnet den Konfliktdialog ohne stilles Überschreiben. Die Anzeige ist keine Rechtequelle: der Server prüft jede Anfrage erneut.
 * Belege in Quarantäne und der Zustand „XML-Konflikt“ (E-Rechnung, Offener Punkt 4) sind in M4 nicht Teil der Freigabe.
 */
export function Belegpruefung() {
  const api = useApi();
  const session = useSession();
  const [filter, setFilter] = useState('');
  type ListData = { items: ExpenseSummary[]; asOf: string };
  // Das Ergebnis gehört zu Filter und Ladezähler; für einen anderen Stand gilt wieder „lädt“ (kein Zustand in der Wirkung)
  const [listResult, setList] = useState<
    { key: string; value: ResourceState<ListData> } | undefined
  >();
  const [selected, setSelected] = useState<string | undefined>();
  const [detail, setDetail] = useState<Detail | undefined>();
  const [conflict, setConflict] = useState(false);
  const [tick, setTick] = useState(0);
  /** Zählt nur ausdrückliches (Neu-)Laden eines Belegs; die Teilmasken werden dann neu aufgebaut, nach eigenen Änderungen bleibt ihre Rückmeldung stehen. */
  const [version, setVersion] = useState(0);
  const status =
    detail?.status === 'ready'
      ? (STATUS_LABEL[detail.data.state] ?? detail.data.state)
      : 'Arbeitsliste';
  const context = useScreenContext({ tone: 'info', label: status });

  const me = session.status === 'ready' ? session.me : undefined;
  const scopes = new Set(me?.scopes ?? []);
  const canWrite = scopes.has('receipts:write');

  const listKey = `${filter}|${tick}`;
  const list: ResourceState<ListData> =
    listResult?.key === listKey ? listResult.value : { status: 'loading' };

  useEffect(() => {
    let cancelled = false;
    api
      .call('listExpenses', {
        query: { limit: 50, ...(filter ? { state: filter as 'STORED' } : {}) },
      })
      .then(
        (p) =>
          !cancelled &&
          setList({
            key: listKey,
            value: { status: 'ready', data: { items: p.items, asOf: p.as_of } },
          }),
      )
      .catch(
        (e: unknown) =>
          !cancelled &&
          setList({
            key: listKey,
            value: { status: 'error', error: e instanceof ApiError ? e : { status: 0 } },
          }),
      );
    return () => {
      cancelled = true;
    };
  }, [api, filter, listKey]);

  const load = useCallback(
    (id: string) => {
      setVersion((n) => n + 1);
      setDetail({ status: 'loading' });
      api
        .call('getExpense', { path: { expense_id: id } })
        .then((data) => setDetail({ status: 'ready', data }))
        .catch((e: unknown) =>
          setDetail({ status: 'error', error: e instanceof ApiError ? e : new ApiError(0) }),
        );
    },
    [api],
  );

  const choose = (id: string) => {
    setSelected(id);
    load(id);
  };

  const changed = (e: Expense) => {
    setDetail({ status: 'ready', data: e });
    setTick((n) => n + 1);
  };

  if (!context) return null;
  const asOf =
    list.status === 'ready' ? zeit.toRfc3339(Date.parse(list.data.asOf), DEFAULT_ZONE) : undefined;

  return (
    <ScreenFrame title="Belege prüfen und freigeben" context={context}>
      <p>
        {me && hasRight(me, 'finance')
          ? 'Rechtsträger, Belegstatus und Datenstand stehen im Kontextbalken.'
          : 'Für diese Sitzung ist die Belegprüfung nicht freigeschaltet.'}
      </p>
      <section aria-label="Arbeitsliste">
        <h2>Arbeitsliste</h2>
        <div>
          <label htmlFor="belege-status">Status</label>
          <select id="belege-status" value={filter} onChange={(e) => setFilter(e.target.value)}>
            {STATUS_FILTER.map((f) => (
              <option key={f.value} value={f.value}>
                {f.label}
              </option>
            ))}
          </select>
        </div>
        {asOf && <p>Datenstand der Liste: {asOf}</p>}
        <ResourceView
          state={list}
          isEmpty={(d) => d.items.length === 0}
          empty={{
            title: 'Keine Belege in diesem Status',
            hint: 'Ändern Sie den Statusfilter oder laden Sie einen Beleg hoch.',
          }}
          errorAction={
            <button type="button" onClick={() => setTick((n) => n + 1)}>
              Erneut versuchen
            </button>
          }
        >
          {(d) => (
            <table aria-label="Belege">
              <thead>
                <tr>
                  <th scope="col">Händler</th>
                  <th scope="col">Datum</th>
                  <th scope="col">Betrag</th>
                  <th scope="col">Status</th>
                  <th scope="col">Aktion</th>
                </tr>
              </thead>
              <tbody>
                {d.items.map((b) => (
                  <tr
                    key={b.document_id}
                    aria-current={b.document_id === selected ? 'true' : undefined}
                  >
                    <th scope="row">{b.merchant ?? 'Händler fehlt'}</th>
                    <td>{b.document_date ?? '–'}</td>
                    <td>
                      {b.amount_minor !== null
                        ? formatMinor(b.amount_minor, b.currency ?? 'EUR')
                        : '–'}
                    </td>
                    <td>
                      <StatusBadge tone={b.state === 'POSTED' ? 'ok' : 'neutral'}>
                        {STATUS_LABEL[b.state] ?? b.state}
                      </StatusBadge>
                    </td>
                    <td>
                      <button type="button" onClick={() => choose(b.document_id)}>
                        Prüfen: {b.merchant ?? 'Beleg ohne Händler'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ResourceView>
      </section>

      {me && scopes.has('receipts:write') && list.status === 'ready' && (
        <BelegUpload
          legalEntityId={list.data.items[0]?.legal_entity_id ?? ''}
          canWrite={canWrite && Boolean(list.data.items[0]?.legal_entity_id)}
          onCreated={(e) => {
            setTick((n) => n + 1);
            if (e) choose(e.document_id);
          }}
        />
      )}

      {detail?.status === 'loading' && <p role="status">Beleg wird geladen …</p>}
      {detail?.status === 'error' && (
        <p role="alert">
          {detail.error.status === 404
            ? 'Der Beleg wurde nicht gefunden oder ist für Sie nicht sichtbar (404).'
            : detail.error.status === 403
              ? 'Dafür fehlt das Recht (403).'
              : 'Der Beleg konnte nicht geladen werden.'}{' '}
          <button type="button" onClick={() => selected && load(selected)}>
            Erneut versuchen
          </button>
        </p>
      )}
      {detail?.status === 'ready' && (
        <article aria-label="Beleg">
          <h2>Beleg {detail.data.fields.merchant ?? detail.data.document_id}</h2>
          <p>
            Rechtsträger {detail.data.legal_entity_id ?? '–'} · Status{' '}
            {STATUS_LABEL[detail.data.state] ?? detail.data.state} · Revision {detail.data.revision}
          </p>
          <div
            style={{
              display: 'grid',
              gap: '1rem',
              gridTemplateColumns: 'repeat(auto-fit, minmax(20rem, 1fr))',
            }}
          >
            <Original expenseId={detail.data.document_id} mime={detail.data.mime} />
            <div>
              <OcrFelder expense={detail.data} />
              <section aria-label="Kostenobjekte">
                <h3>Kostenzuordnung</h3>
                {detail.data.cost_objects.length === 0 ? (
                  <p>Es fehlt die Kostenstelle oder das Fahrzeug.</p>
                ) : (
                  <ul>
                    {detail.data.cost_objects.map((o) => (
                      <li key={o.id}>
                        {o.kind === 'VEHICLE' ? 'Fahrzeug' : 'Kostenstelle'} {o.name ?? o.reference}
                        {o.active ? '' : ' (inaktiv)'}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <Pruefhinweise
                expense={detail.data}
                onChanged={changed}
                onConflict={() => setConflict(true)}
              />
              <Aufteilung
                key={`a-${detail.data.document_id}-${version}`}
                expense={detail.data}
                canEdit={canWrite}
                onChanged={changed}
                onConflict={() => setConflict(true)}
              />
              <BelegFreigabe
                key={`f-${detail.data.document_id}-${version}`}
                expense={detail.data}
                onChanged={changed}
                onConflict={() => setConflict(true)}
              />
              <Gegenbeleg expense={detail.data} />
            </div>
          </div>
        </article>
      )}
      <ConflictDialog
        open={conflict}
        onReload={() => {
          setConflict(false);
          if (selected) load(selected);
        }}
        onReview={() => {
          setConflict(false);
          if (selected) load(selected);
        }}
        onCancel={() => setConflict(false)}
      />
    </ScreenFrame>
  );
}
