import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  OperationId,
  OwnShift,
  RuleHint,
  TimeEvent,
  TimeTargetActualDay,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { hm, mondayOf } from './EigeneZeit';

const SHIFT: OwnShift = {
  shift_id: '00000000-0000-4000-8000-0000000000f1',
  roster_id: '00000000-0000-4000-8000-0000000000e1',
  roster_revision: 3,
  operating_day: '2026-12-01',
  starts_at: '2026-12-01T05:00:00Z',
  ends_at: '2026-12-01T13:00:00Z',
  timezone: 'Europe/Berlin',
  function: 'Fahrdienst',
  state: 'PUBLISHED',
  break_minutes: 30,
};

const day = (d: string, o: Partial<TimeTargetActualDay> = {}): TimeTargetActualDay => ({
  day: d,
  planned: [],
  planned_seconds: 0,
  actual: [],
  actual_net_seconds: null,
  flags: [],
  hints: [],
  ...o,
});

const HINT: RuleHint = {
  rule: 'REST_PERIOD_SHORT',
  basis: 'PLAN',
  person_ref: 'p',
  expected: 660,
  actual: 659,
  unit: 'MINUTES',
  day: '2026-12-01',
  from: '2026-12-01T11:00:00Z',
  to: '2026-12-01T21:59:00Z',
  shift_ids: [],
  event_ids: [],
  message: 'Ruhezeit unterschritten',
};

const event = (
  id: string,
  kind: TimeEvent['kind'],
  at: string,
  o: Partial<TimeEvent> = {},
): TimeEvent => ({
  event_id: id,
  person_ref: 'user-1',
  kind,
  occurred_at: at,
  device_recorded_at: at,
  received_at: at,
  source: 'APP',
  recorded_by: 'user-1',
  shift_id: null,
  backfill_reason: null,
  time_zone: 'Europe/Berlin',
  occurred_at_local: {
    local: at,
    offset: '+01:00',
    operating_day: at.slice(0, 10),
    ambiguous: false,
  },
  ...o,
});

type Data = {
  shifts?: OwnShift[];
  days?: TimeTargetActualDay[];
  events?: TimeEvent[];
  absences?: {
    id: string;
    person_ref: string;
    from: string;
    to: string;
    availability: 'UNAVAILABLE';
  }[];
};

function setup(data: Data, fail?: Partial<Record<OperationId, ApiError>>) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({ roles: ['driver'], scopes: ['me:read', 'time-events:read'] }),
        );
      const err = fail?.[id];
      if (err) return Promise.reject(err);
      if (id === 'listOwnRosterShifts') return Promise.resolve({ items: data.shifts ?? [] });
      if (id === 'listTimeTargetActual')
        return Promise.resolve({
          person_ref: 'user-1',
          timezone: 'Europe/Berlin',
          days: data.days ?? [],
        });
      if (id === 'listTimeEvents')
        return Promise.resolve({
          items: data.events ?? [],
          next_cursor: null,
          as_of: '2026-12-01T00:00:00Z',
        });
      if (id === 'listOwnAbsences')
        return Promise.resolve({
          items: data.absences ?? [],
          next_cursor: null,
          as_of: '2026-12-01T00:00:00Z',
        });
      return Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/zeit']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const setWeek = async (v: string) =>
  fireEvent.change(await screen.findByLabelText(/Woche ab/), { target: { value: v } });

describe('UI-13 Eigene Zeit und Abwesenheit (WP-M4-20)', () => {
  it('zeigt eigene Schichten, Soll/Ist, Hinweise, Ereignisse mit Quelle und Abwesenheit, barrierefrei', async () => {
    const { container, calls } = setup({
      shifts: [SHIFT],
      days: [
        day('2026-12-01', {
          planned: [
            {
              shift_id: SHIFT.shift_id,
              starts_at: SHIFT.starts_at,
              ends_at: SHIFT.ends_at,
              timezone: 'Europe/Berlin',
              planned_seconds: 28800,
              state: 'PUBLISHED',
            },
          ],
          planned_seconds: 28800,
          actual: [
            {
              start: '2026-12-01T05:00:00Z',
              end: '2026-12-01T13:30:00Z',
              breaks: [],
              net_seconds: 30600,
              flags: [],
              event_ids: ['e1', 'e2'],
              time_zone: 'Europe/Berlin',
              start_local: null,
              end_local: null,
              operating_day: '2026-12-01',
            },
          ],
          actual_net_seconds: 30600,
          hints: [HINT],
        }),
      ],
      events: [
        event('e1', 'WORK_START', '2026-12-01T05:00:00Z'),
        event('e2', 'WORK_END', '2026-12-01T13:30:00Z', {
          source: 'BACKFILL',
          device_recorded_at: null,
          backfill_reason: 'Nachtrag',
        }),
      ],
      absences: [
        {
          id: 'a1',
          person_ref: 'user-1',
          from: '2026-12-03T00:00:00Z',
          to: '2026-12-05T00:00:00Z',
          availability: 'UNAVAILABLE',
        },
      ],
    });
    await setWeek('2026-11-30');
    expect(await screen.findByText('Meine veröffentlichten Schichten')).toBeTruthy();
    // Schicht in Ortszeit mit Offset und Planrevision
    const shifts = screen.getByRole('table', { name: 'Eigene veröffentlichte Schichten' });
    expect(within(shifts).getByText('01.12.2026, 06:00:00 (UTC+01:00)')).toBeTruthy();
    expect(within(shifts).getByText('3')).toBeTruthy();
    // Soll und Ist getrennt
    const sollIst = screen.getByRole('table', { name: 'Soll und Ist je Tag' });
    expect(within(sollIst).getByText('8:00 h')).toBeTruthy();
    expect(within(sollIst).getByText('8:30 h')).toBeTruthy();
    // Hinweis mit Soll und Ist, ausdrücklich keine Rechtsbewertung
    expect(screen.getByText('Ruhezeit unterschritten')).toBeTruthy();
    expect(screen.getByText(/Soll 660 min, Ist 659 min/)).toBeTruthy();
    expect(screen.getByText(/keine Rechtsbewertung/)).toBeTruthy();
    // Ereignisse mit Quelle
    expect(screen.getByText('Fahrer-App')).toBeTruthy();
    expect(screen.getByText('Nacherfassung durch die Personalrolle')).toBeTruthy();
    expect(screen.getByText('Nacherfasst')).toBeTruthy();
    // Abwesenheit neutral
    const abs = screen.getByRole('table', { name: 'Eigene Abwesenheitszeiträume' });
    expect(within(abs).getByText('nicht verfügbar')).toBeTruthy();
    // P1: Antrag und Korrektur sind deaktiviert
    expect(
      (screen.getByRole('button', { name: 'Antrag stellen' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Korrektur beantragen' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    // keine Personenauswahl: alle Abfragen laufen ohne person_ref
    expect(
      calls
        .filter((c) => c.id !== 'getMe')
        .every((c) => !JSON.stringify(c.args).includes('person_ref')),
    ).toBe(true);
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('zeigt fehlende Ist-Buchung und Konflikt als eigene Zustände, der Plan ersetzt nie das Ist', async () => {
    setup({
      days: [
        day('2026-12-01', {
          planned: [
            {
              shift_id: 's',
              starts_at: SHIFT.starts_at,
              ends_at: SHIFT.ends_at,
              timezone: 'Europe/Berlin',
              planned_seconds: 28800,
              state: 'PUBLISHED',
            },
          ],
          planned_seconds: 28800,
          flags: ['NO_ACTUAL_BOOKING'],
        }),
        day('2026-12-02', {
          actual: [
            {
              start: '2026-12-02T05:00:00Z',
              end: null,
              breaks: [],
              net_seconds: null,
              flags: ['MISSING_END'],
              event_ids: ['e9'],
              time_zone: 'Europe/Berlin',
              start_local: null,
              end_local: null,
              operating_day: '2026-12-02',
            },
          ],
          flags: ['ACTUAL_WITHOUT_PLAN', 'ACTUAL_INCOMPLETE'],
        }),
      ],
      events: [
        event('e9', 'WORK_START', '2026-12-02T05:00:00Z', { received_at: '2026-12-02T09:00:00Z' }),
      ],
    });
    await setWeek('2026-11-30');
    expect(await screen.findByText('Fehlende Buchung')).toBeTruthy();
    expect(screen.getByText('Konflikt: Buchung unvollständig')).toBeTruthy();
    expect(screen.getByText('Ist ohne Plan')).toBeTruthy();
    const table = screen.getByRole('table', { name: 'Soll und Ist je Tag' });
    const rows = within(table).getAllByRole('row');
    // Tag mit Soll, aber ohne Buchung: Soll 8:00 h, Ist ausdrücklich „keine vollständige Buchung“, nie 8:00 h
    expect(within(rows[1] as HTMLElement).getByText('8:00 h')).toBeTruthy();
    expect(within(rows[1] as HTMLElement).getByText('keine vollständige Buchung')).toBeTruthy();
    // das Ereignis im unvollständigen Intervall ist als Konflikt gekennzeichnet und als verzögert übertragen (offline)
    const events = screen.getByRole('table', { name: 'Eigene Zeitereignisse' });
    expect(within(events).getByText('Konflikt')).toBeTruthy();
    expect(within(events).getByText('Verzögert übertragen (offline erfasst)')).toBeTruthy();
  });

  it('zeigt DST-Tage mit eindeutigem Offset (AT-47)', async () => {
    setup({
      shifts: [
        {
          ...SHIFT,
          operating_day: '2026-10-25',
          starts_at: '2026-10-25T00:30:00Z',
          ends_at: '2026-10-25T08:30:00Z',
        },
      ],
      days: [day('2026-10-25')],
      events: [
        event('a', 'WORK_START', '2026-10-25T00:30:00Z', {
          occurred_at_local: {
            local: '2026-10-25T02:30:00+02:00',
            offset: '+02:00',
            operating_day: '2026-10-25',
            ambiguous: true,
          },
        }),
        event('b', 'WORK_END', '2026-10-25T01:30:00Z', {
          occurred_at_local: {
            local: '2026-10-25T02:30:00+01:00',
            offset: '+01:00',
            operating_day: '2026-10-25',
            ambiguous: true,
          },
        }),
      ],
    });
    await setWeek('2026-10-19');
    const shifts = await screen.findByRole('table', { name: 'Eigene veröffentlichte Schichten' });
    expect(within(shifts).getByText('25.10.2026, 02:30:00 (UTC+02:00)')).toBeTruthy();
    // dieselbe Ortszeit 02:30 kommt zweimal vor und ist über den Offset unterscheidbar
    const events = screen.getByRole('table', { name: 'Eigene Zeitereignisse' });
    expect(within(events).getByText('25.10.2026, 02:30:00 (UTC+02:00)')).toBeTruthy();
    expect(within(events).getByText('25.10.2026, 02:30:00 (UTC+01:00)')).toBeTruthy();
    expect(within(events).getAllByText(/Ortszeit doppelt/).length).toBe(2);
  });

  it('Leerzustand und Fehlerzustände ohne Daten', async () => {
    const empty = setup({});
    expect(await screen.findByText('Keine Einträge in dieser Woche')).toBeTruthy();
    empty.unmount();
    setup({}, { listOwnRosterShifts: new ApiError(403) });
    await waitFor(() => expect(screen.queryByText('Meine veröffentlichten Schichten')).toBeNull());
    expect(screen.queryByText('Keine Einträge in dieser Woche')).toBeNull();
  });

  it('Hilfsfunktionen: Montag der Woche und Dauer', () => {
    expect(mondayOf(new Date(2026, 11, 3))).toBe('2026-11-30');
    expect(mondayOf(new Date(2026, 11, 6))).toBe('2026-11-30');
    expect(mondayOf(new Date(2026, 11, 7))).toBe('2026-12-07');
    expect(hm(30600)).toBe('8:30 h');
    expect(hm(0)).toBe('0:00 h');
  });
});
