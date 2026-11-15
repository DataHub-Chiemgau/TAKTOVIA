import { ApiError, CommandKeys, runCommand, useApi } from '@taktovia/api-client';
import type { IncidentOutage, OutageBooking } from '@taktovia/api-client';
import { DataTable, StatusBadge } from '@taktovia/design-system';
import type { StatusTone } from '@taktovia/design-system';
import { useRef, useState } from 'react';

const STATE: Readonly<Record<string, { text: string; tone: StatusTone }>> = {
  OPEN: { text: 'offen', tone: 'warning' },
  PROPOSED: { text: 'Ersatz vorgeschlagen', tone: 'info' },
  REBOOKED: { text: 'umgebucht', tone: 'ok' },
  CANCELLED: { text: 'storniert', tone: 'ok' },
  OPEN_ISSUE: { text: 'offener Punkt', tone: 'error' },
  BOARDED: { text: 'eingestiegen', tone: 'warning' },
  ONBOARD_DONE: { text: 'eingestiegen, Fahrt beendet', tone: 'ok' },
};
const ISSUE: Readonly<Record<string, string>> = {
  NO_REPLACEMENT: 'kein geeignetes Ersatzfahrzeug',
  RUN_NOT_CHANGEABLE: 'Umlauf nicht mehr änderbar',
};

/** Fehlertext einer Aktion: bereits entschieden (409), überholter Stand (412) und fehlendes Recht (403) sind getrennt benannt, nie ein stilles Überschreiben. */
function failure(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 412)
      return 'Der Fall wurde zwischenzeitlich von anderer Stelle entschieden oder geändert; die Ansicht wurde aktualisiert.';
    if (err.status === 409)
      return 'Die Aktion ist in diesem Zustand nicht möglich (bereits entschieden, Fahrgast eingestiegen oder Fenster verletzt).';
    if (err.status === 403) return 'Dafür fehlt Ihnen das Recht (Disposition).';
  }
  return 'Die Aktion konnte nicht ausgeführt werden. Bitte erneut versuchen.';
}

const time = (iso: string) => new Date(iso).toLocaleTimeString('de-DE', { timeStyle: 'short' });

/**
 * UI-07 Störungsansicht (WP-M3-54, DMG-010): betroffene Fahrgäste eines Fahrzeugausfalls mit Zustand, Ersatzvorschlägen (Wirkung auf das Zusagefenster) und den Aktionen „Ersatzoptionen prüfen“,
 * Umbuchen, Stornieren wegen Fahrzeugausfall und „Fahrgast informieren“. Eingestiegene Fahrgäste stehen gesondert und ohne Aktion (nie automatisch umgebucht oder storniert). Die Aktionen laufen mit
 * `If-Match` der betroffenen Buchung und `Idempotency-Key` je Absicht; die Anzeige ist keine Rechtequelle (der Server prüft Rolle und Dienst).
 */
export function Stoerungsansicht(props: {
  incidentId: string;
  outage: IncidentOutage;
  mayDecide: boolean;
  onChanged: () => void;
  schadenakten?: readonly string[] | undefined;
}) {
  const api = useApi();
  const keys = useRef(new CommandKeys());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [done, setDone] = useState<string | undefined>(undefined);

  const act = (intent: string, run: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError(undefined);
    setDone(undefined);
    run()
      .then(() => {
        setDone(ok);
        props.onChanged();
      })
      .catch((e: unknown) => {
        setError(failure(e));
        if (e instanceof ApiError && (e.status === 412 || e.status === 409)) props.onChanged();
      })
      .finally(() => setBusy(false));
    void intent;
  };

  const path = (b: OutageBooking) => ({ incident_id: props.incidentId, booking_id: b.booking_id });
  const ifMatch = (b: OutageBooking) => ({ 'If-Match': `"${b.revision}"`, 'Idempotency-Key': '' });

  const check = () =>
    act(
      'check',
      () =>
        runCommand(api, keys.current, `check:${props.incidentId}`, 'checkOperatorIncidentOutage', {
          path: { incident_id: props.incidentId },
          headers: { 'Idempotency-Key': '' },
        }),
      'Ersatzoptionen wurden neu berechnet.',
    );

  if (props.outage.bookings.length === 0)
    return (
      <section aria-label="Störungsansicht">
        <h2>Betroffene Fahrgäste</h2>
        <p>Von diesem Ausfall ist keine offene Fahrt betroffen.</p>
      </section>
    );
  const boarded = props.outage.bookings.filter(
    (b) => b.state === 'BOARDED' || b.state === 'ONBOARD_DONE',
  );
  return (
    <section aria-label="Störungsansicht">
      <h2>Betroffene Fahrgäste</h2>
      <p>
        {props.outage.open_count} Buchungen offen.{' '}
        {props.mayDecide ? (
          <button type="button" disabled={busy} onClick={check}>
            Ersatzoptionen prüfen
          </button>
        ) : (
          <small>Entscheiden dürfen nur Disponentinnen und Disponenten.</small>
        )}
      </p>
      {props.schadenakten && props.schadenakten.length > 0 ? (
        <p>Schadenakten: {props.schadenakten.join(', ')}</p>
      ) : null}
      {boarded.length > 0 ? (
        <p role="note">
          {boarded.length} Fahrgast/Fahrgäste bereits eingestiegen: sie werden nicht automatisch
          umgebucht oder storniert; die Leitstelle klärt das Vorgehen mit dem Fahrer.
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {done ? <p role="status">{done}</p> : null}
      <DataTable
        caption="Betroffene Buchungen des gesperrten Fahrzeugs"
        rows={props.outage.bookings}
        rowKey={(b) => b.id}
        columns={[
          { key: 'no', header: 'Buchung', render: (b) => b.booking_number ?? b.booking_id },
          {
            key: 'pax',
            header: 'Fahrgäste',
            render: (b) =>
              `${b.passengers}${(b.wheelchair_spaces ?? 0) > 0 ? ` (davon ${b.wheelchair_spaces} Rollstuhl)` : ''}`,
          },
          {
            key: 'window',
            header: 'Abholfenster',
            render: (b) =>
              b.pickup_window
                ? `${time(b.pickup_window.earliest_at)} bis ${time(b.pickup_window.latest_at)}`
                : '–',
          },
          {
            key: 'state',
            header: 'Zustand',
            render: (b) => {
              const s = STATE[b.state] ?? { text: b.state, tone: 'info' as StatusTone };
              return (
                <>
                  <StatusBadge tone={s.tone}>{s.text}</StatusBadge>
                  {b.issue_code ? ` ${ISSUE[b.issue_code] ?? b.issue_code}` : ''}
                </>
              );
            },
          },
          {
            key: 'proposals',
            header: 'Ersatzvorschläge',
            render: (b) =>
              b.proposals.length === 0 ? (
                b.state === 'PROPOSED' || b.state === 'OPEN' || b.state === 'OPEN_ISSUE' ? (
                  'keine'
                ) : (
                  '–'
                )
              ) : (
                <ul>
                  {b.proposals.map((p) => (
                    <li key={p.id}>
                      Fahrzeug {p.vehicle_id}, Abholung {time(p.pickup_at)}{' '}
                      <StatusBadge tone={p.window_effect === 'IN_WINDOW' ? 'ok' : 'warning'}>
                        {p.window_effect === 'IN_WINDOW'
                          ? 'im Zusagefenster'
                          : 'außerhalb des Zusagefensters'}
                      </StatusBadge>{' '}
                      {props.mayDecide && b.actions.includes('REBOOK') ? (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            act(
                              'rebook',
                              () =>
                                runCommand(
                                  api,
                                  keys.current,
                                  `rebook:${b.id}:${p.id}`,
                                  'rebookOutageBooking',
                                  {
                                    path: path(b),
                                    headers: ifMatch(b),
                                    body: {
                                      proposal_id: p.id,
                                      reason_code: 'VEHICLE_OUTAGE',
                                      accept_window_effect: p.window_effect === 'OUTSIDE_WINDOW',
                                    },
                                  },
                                ),
                              'Die Buchung wurde umgebucht; die Ersatzfahrt gilt jetzt als zugesagt.',
                            )
                          }
                        >
                          Umbuchen
                        </button>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ),
          },
          {
            key: 'actions',
            header: 'Aktionen',
            render: (b) =>
              props.mayDecide ? (
                <>
                  {b.actions.includes('CANCEL') ? (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() =>
                        act(
                          'cancel',
                          () =>
                            runCommand(api, keys.current, `cancel:${b.id}`, 'cancelOutageBooking', {
                              path: path(b),
                              headers: ifMatch(b),
                              body: { reason_code: 'VEHICLE_OUTAGE' },
                            }),
                          'Die Buchung wurde wegen Fahrzeugausfall storniert; Erstattung und Benachrichtigung folgen dem Stornoweg.',
                        )
                      }
                    >
                      Stornieren (Fahrzeugausfall)
                    </button>
                  ) : null}
                  {b.actions.includes('INFORM_PASSENGER') ? (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() =>
                        act(
                          'inform',
                          () =>
                            runCommand(
                              api,
                              keys.current,
                              `inform:${b.id}:${b.revision}`,
                              'informOutageBooking',
                              {
                                path: path(b),
                                headers: ifMatch(b),
                              },
                            ),
                          'Die Mitteilung an den Fahrgast wurde angefordert.',
                        )
                      }
                    >
                      Fahrgast informieren
                    </button>
                  ) : null}
                  {b.passenger_notice_requested ? <small>Fahrgast informiert</small> : null}
                </>
              ) : (
                '–'
              ),
          },
        ]}
      />
    </section>
  );
}
