import { ApiError, useApi } from '@taktovia/api-client';
import type {
  AvailabilityRestriction,
  OwnShift,
  RuleHint,
  TimeEvent,
  TimeTargetActualDay,
} from '@taktovia/api-client';
import { DataTable, ResourceView, ScreenFrame, StatusBadge, zeit } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

/** Zone, solange `reporting_timezone` je Mandant noch nicht angebunden ist (wie das Backend, Pilotbetrieb in Deutschland). */
const ZONE = 'Europe/Berlin';
/** Ein Ereignis, das später als so viele Minuten nach der Gerätezeit eintrifft, gilt als verzögert übertragen (vermutlich offline erfasst). */
export const OFFLINE_MINUTES = 5;

type Week = {
  shifts: OwnShift[];
  days: TimeTargetActualDay[];
  events: TimeEvent[];
  absences: AvailabilityRestriction[];
};

const KIND: Readonly<Record<TimeEvent['kind'], string>> = {
  WORK_START: 'Arbeitsbeginn',
  WORK_END: 'Arbeitsende',
  BREAK_START: 'Pausenbeginn',
  BREAK_END: 'Pausenende',
};
const SOURCE: Readonly<Record<TimeEvent['source'], string>> = {
  APP: 'Fahrer-App',
  WEB: 'Web',
  BACKFILL: 'Nacherfassung durch die Personalrolle',
};
const RULE: Readonly<Record<RuleHint['rule'], string>> = {
  REST_PERIOD_SHORT: 'Ruhezeit unterschritten',
  DAILY_WORK_EXCEEDED: 'Tagesarbeitszeit überschritten',
  BREAK_MISSING: 'Pflichtpause fehlt',
};

/** Montag der Woche des Datums (lokales Datum `yyyy-mm-dd`). */
export function mondayOf(date: Date): string {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}

const addDays = (day: string, n: number): string => {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

/** Dauer als `h:mm` (Stunden:Minuten). */
export const hm = (seconds: number): string => {
  const m = Math.floor(seconds / 60);
  return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')} h`;
};

/** Ortszeit in der Zone des Datensatzes mit eindeutigem Offset (Sommerzeit-/Winterzeit-Tage, AT-47). */
const local = (iso: string, zone: string): string => zeit.formatLocal(Date.parse(iso), zone);

/**
 * UI-13 Eigene Zeit/Abwesenheit, Grundausbau (WP-M4-20): eigene veröffentlichte Schichten, Zeitereignisse mit Quelle und Offline-/Konfliktkennzeichen, Soll/Ist je Tag, Hinweise zu Pausen und
 * Ruhezeit (WP-M4-13) und eigene Abwesenheitszeiträume. Es gibt keine Personenauswahl: der Server bindet jede Abfrage an die angemeldete Person (fremde Personen-IDs ergeben dort 404).
 * Der Plan überschreibt nie eine Ist-Zeit (Kap. 06 §6.8): ohne vollständige Buchung steht „keine Buchung“, nie der Planwert. „Antrag“ und „Korrektur“ sind in P1 deaktiviert (M7).
 * Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage.
 */
export function EigeneZeit() {
  const api = useApi();
  const context = useScreenContext({ tone: 'info', label: 'Eigene Zeit und Abwesenheit' });
  const [week, setWeek] = useState(() => mondayOf(new Date()));
  const [state, setState] = useState<ResourceState<Week>>({ status: 'loading' });

  const changeWeek = (value: string) => {
    if (!value) return;
    setState({ status: 'loading' });
    setWeek(value);
  };

  useEffect(() => {
    let cancelled = false;
    const to = addDays(week, 6);
    const windowFrom = `${addDays(week, -1)}T00:00:00Z`;
    const windowTo = `${addDays(week, 8)}T00:00:00Z`;
    Promise.all([
      api.call('listOwnRosterShifts', { query: { from: week, to } }),
      api.call('listTimeTargetActual', { query: { from: week, to, timezone: ZONE } }),
      api.call('listTimeEvents', { query: { from: windowFrom, to: windowTo, limit: 100 } }),
      api.call('listOwnAbsences', { query: { from: windowFrom, to: windowTo, limit: 100 } }),
    ])
      .then(([shifts, target, events, absences]) => {
        if (!cancelled)
          setState({
            status: 'ready',
            data: {
              shifts: shifts.items,
              days: target.days,
              events: events.items,
              absences: absences.items,
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
  }, [api, week]);

  if (!context) return null;
  return (
    <ScreenFrame title="Eigene Zeit und Abwesenheit" context={context}>
      <p>
        <label>
          Woche ab (Montag){' '}
          <input type="date" value={week} onChange={(e) => changeWeek(e.target.value)} />
        </label>
      </p>
      <p>
        <button type="button" disabled aria-describedby="p1-hinweis">
          Antrag stellen
        </button>{' '}
        <button type="button" disabled aria-describedby="p1-hinweis">
          Korrektur beantragen
        </button>{' '}
        <span id="p1-hinweis">Anträge und Korrekturen sind erst mit M7 verfügbar.</span>
      </p>
      <ResourceView
        state={state}
        isEmpty={(w) =>
          w.shifts.length + w.events.length + w.absences.length === 0 &&
          w.days.every((d) => d.planned.length === 0 && d.actual.length === 0)
        }
        empty={{
          title: 'Keine Einträge in dieser Woche',
          hint: 'Es gibt keine veröffentlichte Schicht, keine Zeitbuchung und keine Abwesenheit in diesem Zeitraum.',
        }}
      >
        {(w) => <WeekView week={w} />}
      </ResourceView>
    </ScreenFrame>
  );
}

function WeekView({ week }: { week: Week }) {
  const hints = week.days.flatMap((d) => d.hints ?? []);
  // Ereignisse in Intervallen mit Kennzeichen (fehlendes Ende, Ende ohne Beginn) gelten als Konflikt
  const conflictEvents = new Set(
    week.days.flatMap((d) =>
      d.actual.filter((i) => i.flags.length > 0).flatMap((i) => i.event_ids),
    ),
  );
  return (
    <>
      <section aria-labelledby="schichten-titel">
        <h2 id="schichten-titel">Meine veröffentlichten Schichten</h2>
        <DataTable
          caption="Eigene veröffentlichte Schichten"
          emptyTitle="Keine veröffentlichte Schicht in dieser Woche"
          rows={week.shifts}
          rowKey={(s) => s.shift_id}
          columns={[
            { key: 'day', header: 'Betriebstag', render: (s) => s.operating_day },
            { key: 'start', header: 'Beginn', render: (s) => local(s.starts_at, s.timezone) },
            { key: 'end', header: 'Ende', render: (s) => local(s.ends_at, s.timezone) },
            { key: 'break', header: 'Geplante Pause', render: (s) => `${s.break_minutes} min` },
            { key: 'rev', header: 'Planrevision', render: (s) => s.roster_revision },
          ]}
        />
      </section>

      <section aria-labelledby="sollist-titel">
        <h2 id="sollist-titel">Soll und Ist</h2>
        <p>
          Das Soll stammt aus dem veröffentlichten Plan, das Ist aus den gestempelten Zeiten. Der
          Plan ersetzt nie eine Ist-Zeit.
        </p>
        <DataTable
          caption="Soll und Ist je Tag"
          rows={week.days}
          rowKey={(d) => d.day}
          columns={[
            { key: 'day', header: 'Tag', render: (d) => d.day },
            {
              key: 'soll',
              header: 'Soll',
              render: (d) => (d.planned.length ? hm(d.planned_seconds) : 'kein Dienst'),
            },
            {
              key: 'ist',
              header: 'Ist',
              render: (d) =>
                d.actual_net_seconds === null
                  ? 'keine vollständige Buchung'
                  : hm(d.actual_net_seconds),
            },
            { key: 'zustand', header: 'Zustand', render: (d) => <DayState day={d} /> },
          ]}
        />
      </section>

      <section aria-labelledby="hinweise-titel">
        <h2 id="hinweise-titel">Hinweise zu Pausen und Ruhezeit</h2>
        <p>
          Hinweise sind Warnungen zur Prüfung und keine Rechtsbewertung; sie ändern weder Plan noch
          Ist-Zeiten.
        </p>
        {hints.length === 0 ? (
          <p>Keine Hinweise für diese Woche.</p>
        ) : (
          <ul>
            {hints.map((h) => (
              <li key={`${h.rule}-${h.basis}-${h.from}`}>
                <StatusBadge tone="warning">{RULE[h.rule]}</StatusBadge>{' '}
                {h.basis === 'PLAN' ? 'im Plan' : 'in den Ist-Zeiten'} am {h.day}: Soll {h.expected}{' '}
                min, Ist {h.actual} min.
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="ereignisse-titel">
        <h2 id="ereignisse-titel">Meine Zeitereignisse</h2>
        <DataTable
          caption="Eigene Zeitereignisse"
          emptyTitle="Keine Zeitereignisse in dieser Woche"
          rows={week.events}
          rowKey={(e) => e.event_id}
          columns={[
            {
              key: 'zeit',
              header: 'Zeitpunkt (Ortszeit mit Offset)',
              render: (e) => local(e.occurred_at, e.time_zone),
            },
            { key: 'art', header: 'Art', render: (e) => KIND[e.kind] },
            { key: 'quelle', header: 'Quelle', render: (e) => SOURCE[e.source] },
            {
              key: 'kennzeichen',
              header: 'Kennzeichen',
              render: (e) => <EventMarks event={e} conflict={conflictEvents.has(e.event_id)} />,
            },
          ]}
        />
      </section>

      <section aria-labelledby="abwesenheit-titel">
        <h2 id="abwesenheit-titel">Meine Abwesenheit</h2>
        <DataTable
          caption="Eigene Abwesenheitszeiträume"
          emptyTitle="Keine Abwesenheit in diesem Zeitraum"
          rows={week.absences}
          rowKey={(a) => a.id}
          columns={[
            { key: 'von', header: 'Von', render: (a) => local(a.from, ZONE) },
            { key: 'bis', header: 'Bis (ausschließlich)', render: (a) => local(a.to, ZONE) },
            { key: 'status', header: 'Verfügbarkeit', render: () => 'nicht verfügbar' },
          ]}
        />
      </section>
    </>
  );
}

function DayState({ day }: { day: TimeTargetActualDay }) {
  const f = new Set(day.flags);
  const marks = [];
  if (f.has('NO_ACTUAL_BOOKING'))
    marks.push(
      <StatusBadge key="n" tone="warning">
        Fehlende Buchung
      </StatusBadge>,
    );
  if (f.has('ACTUAL_INCOMPLETE'))
    marks.push(
      <StatusBadge key="c" tone="error">
        Konflikt: Buchung unvollständig
      </StatusBadge>,
    );
  if (f.has('ACTUAL_WITHOUT_PLAN'))
    marks.push(
      <StatusBadge key="p" tone="info">
        Ist ohne Plan
      </StatusBadge>,
    );
  if (marks.length === 0) return <StatusBadge tone="ok">in Ordnung</StatusBadge>;
  return <>{marks}</>;
}

function EventMarks({ event, conflict }: { event: TimeEvent; conflict: boolean }) {
  const marks = [];
  const delayed =
    event.device_recorded_at !== null &&
    Date.parse(event.received_at) - Date.parse(event.device_recorded_at) > OFFLINE_MINUTES * 60_000;
  if (delayed)
    marks.push(
      <StatusBadge key="o" tone="info">
        Verzögert übertragen (offline erfasst)
      </StatusBadge>,
    );
  if (event.source === 'BACKFILL')
    marks.push(
      <StatusBadge key="b" tone="info">
        Nacherfasst
      </StatusBadge>,
    );
  if (event.occurred_at_local.ambiguous)
    marks.push(
      <StatusBadge key="a" tone="warning">
        Ortszeit doppelt (Herbstumstellung), Offset maßgeblich
      </StatusBadge>,
    );
  if (conflict)
    marks.push(
      <StatusBadge key="c" tone="error">
        Konflikt
      </StatusBadge>,
    );
  return marks.length === 0 ? <>–</> : <>{marks}</>;
}
