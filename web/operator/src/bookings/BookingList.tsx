import { ApiError, useApi } from '@taktovia/api-client';
import type { OperatorBookingRow, OperatorBookingState, OperatorFlag } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState, StatusTone } from '@taktovia/design-system';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useScreenContext } from '../ScreenContext';
import { BookingEditor } from './BookingEditor';
import type { EditorMode } from './BookingEditor';

/** Zustände einer Buchung in der Sprache der Leitstelle (Automat `booking`, M2). */
export const BOOKING_STATE_LABELS: Readonly<Record<OperatorBookingState, string>> = {
  CONFIRMING: 'In Bestätigung',
  CONFIRMED: 'Bestätigt',
  RECONCILING: 'In Klärung',
  COMPENSATING: 'Wird zurückgenommen',
  CANCEL_PENDING: 'Storno läuft',
  CANCELED: 'Storniert',
  COMPLETED: 'Abgeschlossen',
  FAILED: 'Fehlgeschlagen',
};

const TONES: Readonly<Record<OperatorBookingState, StatusTone>> = {
  CONFIRMING: 'info',
  CONFIRMED: 'ok',
  RECONCILING: 'warning',
  COMPENSATING: 'warning',
  CANCEL_PENDING: 'warning',
  CANCELED: 'neutral',
  COMPLETED: 'neutral',
  FAILED: 'error',
};

const STATES = Object.keys(BOOKING_STATE_LABELS) as OperatorBookingState[];

const CAUSES: Readonly<Record<OperatorFlag['cause'], string>> = {
  PICKUP_OVERDUE: 'Abholung überfällig',
  POSITION_STALE: 'Standort veraltet',
  PLAN_ACK_MISSING: 'Fahrerbestätigung der Planänderung fehlt',
  REVIEW_REQUIRED_OPEN: 'Fahrerereignis wartet auf Klärung',
};

/**
 * Kennzeichen eines Auftrags (WP-M3-42): Verspätung (`OVERDUE`) und ausstehende Information (`INFO_PENDING`) sind getrennte Zustände mit eigenem Text; beide nennen die Zahl der Betroffenen.
 * Der Zustand steht immer als Text, nie nur über Farbe.
 */
export function flagText(f: OperatorFlag): string {
  const head = f.kind === 'OVERDUE' ? 'Überfällig' : 'Information ausstehend';
  return `${head}: ${CAUSES[f.cause]} (${f.affected_count} betroffen)`;
}

/** Hinweise einer Zeile: Kennzeichen (Verspätung und ausstehende Information getrennt) und, nach Feldfreigabe der Rolle, der Unterstützungsbedarf. */
export function notesOf(b: OperatorBookingRow): { key: string; kind: string; text: string }[] {
  // kind ist ein Textmerkmal (data-kind), kein Enum des Vertrags
  const notes: { key: string; kind: string; text: string }[] = b.flags.map((f) => ({
    key: f.id,
    kind: f.kind,
    text: flagText(f),
  }));
  if (b.assistance_codes && b.assistance_codes.length > 0)
    notes.push({
      key: 'assist',
      kind: 'ASSISTANCE',
      text: `Unterstützungsbedarf: ${b.assistance_codes.join(', ')}`,
    });
  return notes;
}

const timeOf = (iso: string): string =>
  new Date(iso).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' });
const clock = (iso: string): string => new Date(iso).toLocaleTimeString('de-DE');

interface Search {
  number: string;
  from: string;
  to: string;
  passenger: string;
  vehicle: string;
  state: string;
}

const EMPTY: Search = { number: '', from: '', to: '', passenger: '', vehicle: '', state: '' };

type SortKey = 'number' | 'state' | 'pickup';

/** `datetime-local` (Ortszeit des Browsers) als RFC 3339 mit Offset. */
const iso = (local: string): string => new Date(local).toISOString();

/**
 * UI-07 Auftragsliste mit Suche (WP-M3-37, LH-6.7-OPS-002): die fünf Suchfelder Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug und Status; mehrere Felder ergeben die Schnittmenge. Die Liste zeigt nur
 * Disponierendes; Fahrgast ist der exakte Verweis (eine Suche über Name oder Kontakt ist offen, OP-26). Zustände: laden, Leerzustand (ausdrücklich, kein Fehlerbild), Fehler mit Wiederholen,
 * fehlendes Recht auf Fahrgastdaten (Hinweis am Feld). Sortierung wirkt auf die geladenen Zeilen; die Reihenfolge des Servers (Anlagezeit) bleibt der Cursor-Schlüssel.
 */
export function BookingList() {
  const api = useApi();
  const context = useScreenContext({ tone: 'ok', label: 'Aufträge' });
  const [draft, setDraft] = useState<Search>(EMPTY);
  const [rows, setRows] = useState<ResourceState<OperatorBookingRow[]>>({ status: 'loading' });
  const [cursor, setCursor] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [sort, setSort] = useState<SortKey>('pickup');
  const [applied, setApplied] = useState<Search>(EMPTY);
  const [editor, setEditor] = useState<{ mode: EditorMode; row?: OperatorBookingRow } | undefined>(
    undefined,
  );
  const [done, setDone] = useState<string | undefined>(undefined);
  const active = useRef<Search>(EMPTY);
  const last = useRef<OperatorBookingRow[] | undefined>(undefined);

  const query = (s: Search, after?: string) => ({
    limit: 50,
    ...(s.number.trim() ? { booking_number: s.number.trim() } : {}),
    ...(s.from ? { from: iso(s.from) } : {}),
    ...(s.to ? { to: iso(s.to) } : {}),
    ...(s.passenger.trim() ? { passenger: s.passenger.trim() } : {}),
    ...(s.vehicle.trim() ? { vehicle_id: s.vehicle.trim() } : {}),
    ...(s.state ? { state: s.state as OperatorBookingState } : {}),
    ...(after ? { cursor: after } : {}),
  });

  const load = useCallback(
    (s: Search, after?: string) => {
      api
        .call('searchOperatorBookings', { query: query(s, after) })
        .then((page) => {
          const data = after && last.current ? [...last.current, ...page.items] : page.items;
          last.current = data;
          setAsOf(page.as_of);
          setCursor(page.next_cursor);
          setNotice(undefined);
          setRows({ status: 'ready', data });
        })
        .catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 403 && s.passenger.trim() !== '') {
            setNotice('Für die Fahrgastsuche fehlt Ihnen das Recht auf Fahrgastdaten.');
            setRows({ status: 'ready', data: [] });
            return;
          }
          if (e instanceof ApiError && e.status === 422) {
            setNotice(
              'Die Suchangaben sind ungültig. Bitte prüfen Sie Zeitraum, Fahrgast und Fahrzeug.',
            );
            setRows({ status: 'ready', data: [] });
            return;
          }
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
        });
    },
    [api],
  );

  const run = (s: Search) => {
    active.current = s;
    setApplied(s);
    last.current = undefined;
    setCursor(null);
    setRows({ status: 'loading' });
    load(s);
  };

  // Erste Anzeige: alle Aufträge der berechtigten Dienste (leere Suche)
  useEffect(() => {
    load(EMPTY);
    // nur beim Einhängen; jede weitere Suche beginnt im Ereignis (run)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sorted = useMemo(() => {
    if (rows.status !== 'ready') return [];
    const copy = [...rows.data];
    if (sort === 'number') copy.sort((a, b) => a.booking_number.localeCompare(b.booking_number));
    else if (sort === 'state') copy.sort((a, b) => a.state.localeCompare(b.state));
    else
      copy.sort((a, b) => a.pickup_window.earliest_at.localeCompare(b.pickup_window.earliest_at));
    return copy;
  }, [rows, sort]);

  if (!context) return null;
  const searching = Object.values(applied).some((v) => v !== '');
  return (
    <ScreenFrame title="Leitstelle: Auftragsliste" context={context}>
      <p>
        Datenstand: {asOf ? clock(asOf) : 'wird geladen'}. Angezeigt werden nur Aufträge Ihrer
        berechtigten Dienste. Mehrere Suchfelder ergeben die Schnittmenge.
      </p>
      <form
        aria-label="Suche"
        onSubmit={(e) => {
          e.preventDefault();
          run(draft);
        }}
        style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'end' }}
      >
        <label>
          Buchungsnummer
          <input
            value={draft.number}
            placeholder="BK-2026-000001"
            onChange={(e) => setDraft({ ...draft, number: e.target.value })}
          />
        </label>
        <label>
          Zeitraum von
          <input
            type="datetime-local"
            value={draft.from}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })}
          />
        </label>
        <label>
          Zeitraum bis
          <input
            type="datetime-local"
            value={draft.to}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })}
          />
        </label>
        <label>
          Fahrgast (Verweis)
          <input
            value={draft.passenger}
            placeholder="Konto-Verweis"
            onChange={(e) => setDraft({ ...draft, passenger: e.target.value })}
          />
        </label>
        <label>
          Fahrzeug (Kennung)
          <input
            value={draft.vehicle}
            onChange={(e) => setDraft({ ...draft, vehicle: e.target.value })}
          />
        </label>
        <label>
          Status
          <select
            value={draft.state}
            onChange={(e) => setDraft({ ...draft, state: e.target.value })}
          >
            <option value="">Alle Status</option>
            {STATES.map((s) => (
              <option key={s} value={s}>
                {BOOKING_STATE_LABELS[s]}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Suchen</button>
        <button
          type="button"
          onClick={() => {
            setDraft(EMPTY);
            run(EMPTY);
          }}
        >
          Zurücksetzen
        </button>
      </form>
      <p>
        <label>
          Sortierung{' '}
          <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)}>
            <option value="pickup">Abholfenster</option>
            <option value="number">Buchungsnummer</option>
            <option value="state">Status</option>
          </select>
        </label>
      </p>
      {notice ? <p role="status">{notice}</p> : null}
      {done ? <p role="status">{done}</p> : null}
      <p>
        <button type="button" onClick={() => setEditor({ mode: 'create' })}>
          Buchung für Fahrgast anlegen
        </button>
      </p>
      {editor ? (
        <BookingEditor
          key={`${editor.mode}:${editor.row?.id ?? 'neu'}`}
          mode={editor.mode}
          {...(editor.row ? { row: editor.row } : {})}
          onDone={(m) => {
            setEditor(undefined);
            setDone(m);
            run(active.current);
          }}
        />
      ) : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine Aufträge gefunden',
          hint: searching
            ? 'Zu dieser Suche gibt es keine Aufträge in Ihren Diensten. Ändern Sie die Suchangaben oder setzen Sie die Suche zurück.'
            : 'In Ihren Diensten liegen derzeit keine Aufträge vor.',
        }}
        errorAction={
          <button type="button" onClick={() => run(active.current)}>
            Erneut versuchen
          </button>
        }
      >
        {() => (
          <>
            <table className="tk-table">
              <caption>Aufträge der berechtigten Dienste</caption>
              <thead>
                <tr>
                  <th scope="col">Buchungsnummer</th>
                  <th scope="col">Status</th>
                  <th scope="col">Abholfenster</th>
                  <th scope="col">Fahrzeug</th>
                  <th scope="col">Personen</th>
                  <th scope="col">Hinweise</th>
                  <th scope="col">Aktionen</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((b) => (
                  <tr key={b.id}>
                    <th scope="row">{b.booking_number}</th>
                    <td>
                      <StatusBadge tone={TONES[b.state]}>
                        {BOOKING_STATE_LABELS[b.state]}
                      </StatusBadge>
                    </td>
                    <td>
                      {timeOf(b.pickup_window.earliest_at)} bis {timeOf(b.pickup_window.latest_at)}
                    </td>
                    <td>{b.vehicle_label ?? 'nicht zugeordnet'}</td>
                    <td>
                      {b.passengers}
                      {b.wheelchair_spaces > 0 ? `, davon ${b.wheelchair_spaces} Rollstuhl` : ''}
                    </td>
                    <td>
                      {notesOf(b).length === 0 ? (
                        '–'
                      ) : (
                        <ul>
                          {notesOf(b).map((n) => (
                            <li key={n.key} data-kind={n.kind}>
                              {n.text}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      {['CONFIRMING', 'CONFIRMED'].includes(b.state) ? (
                        <>
                          <button
                            type="button"
                            aria-label={`${b.booking_number} ändern`}
                            onClick={() => setEditor({ mode: 'change', row: b })}
                          >
                            Ändern
                          </button>{' '}
                          <button
                            type="button"
                            aria-label={`${b.booking_number} stornieren`}
                            onClick={() => setEditor({ mode: 'cancel', row: b })}
                          >
                            Stornieren
                          </button>
                        </>
                      ) : (
                        '–'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {cursor ? (
              <p>
                <button type="button" onClick={() => load(active.current, cursor)}>
                  Weitere Aufträge laden
                </button>
              </p>
            ) : null}
          </>
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
