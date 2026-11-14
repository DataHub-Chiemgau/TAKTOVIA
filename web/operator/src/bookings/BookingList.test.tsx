import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, OperatorBookingRow } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const row = (n: number, over: Partial<OperatorBookingRow> = {}): OperatorBookingRow => ({
  id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  booking_number: `BK-2026-0000${String(n).padStart(2, '0')}`,
  service_id: '00000000-0000-4000-8000-00000000aaaa',
  state: 'CONFIRMED',
  revision: 2,
  created_at: '2026-12-01T06:00:00Z',
  pickup_window: { earliest_at: '2026-12-01T09:00:00Z', latest_at: '2026-12-01T09:15:00Z' },
  arrival_window: { earliest_at: '2026-12-01T09:20:00Z', latest_at: '2026-12-01T09:45:00Z' },
  passengers: 2,
  wheelchair_spaces: 0,
  vehicle_id: '00000000-0000-4000-8000-0000000000f1',
  vehicle_label: 'KA-101',
  flags: [],
  ...over,
});

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
        );
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/leitstelle/auftraege']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: OperatorBookingRow[], next: string | null = null) => ({
  items,
  next_cursor: next,
  as_of: '2026-12-01T08:05:00Z',
});

describe('UI-07 Auftragsliste mit Suche (WP-M3-37, LH-6.7-OPS-002)', () => {
  it('leerzustand_und_fuenf_suchfelder_sichtbar', async () => {
    const { calls, container } = setup((id, args) => {
      if (id !== 'searchOperatorBookings') throw new ApiError(404);
      const q = (args.query ?? {}) as Record<string, unknown>;
      return q.booking_number === 'BK-1999-000001' ? page([]) : page([row(1), row(2)]);
    });
    await screen.findByRole('table', { name: /Aufträge der berechtigten Dienste/ });
    // die fünf Suchfelder: Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug, Status
    const form = screen.getByRole('form', { name: 'Suche' });
    for (const label of [
      'Buchungsnummer',
      'Zeitraum von',
      'Zeitraum bis',
      'Fahrgast (Verweis)',
      'Fahrzeug (Kennung)',
      'Status',
    ])
      expect(within(form).getByLabelText(label)).toBeTruthy();
    expect(screen.getByText(/Datenstand: /)).toBeTruthy();
    // Suche mit mehreren Feldern: alle Angaben gehen als Filter an den Server (Schnittmenge dort)
    fireEvent.change(screen.getByLabelText('Buchungsnummer'), {
      target: { value: 'BK-1999-000001' },
    });
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'CONFIRMED' } });
    fireEvent.click(screen.getByRole('button', { name: 'Suchen' }));
    // ausdrücklicher Leerzustand, kein Fehlerbild; der Datenstand bleibt sichtbar
    await screen.findByText('Keine Aufträge gefunden');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(/Zu dieser Suche gibt es keine Aufträge/)).toBeTruthy();
    expect(screen.getByText(/Datenstand: \d/)).toBeTruthy();
    expect(calls.filter((c) => c.id === 'searchOperatorBookings').at(-1)?.args).toMatchObject({
      query: { booking_number: 'BK-1999-000001', state: 'CONFIRMED' },
    });
    // Zurücksetzen holt die Liste wieder
    fireEvent.click(screen.getByRole('button', { name: 'Zurücksetzen' }));
    await screen.findByRole('rowheader', { name: 'BK-2026-000001' });
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('fahrgastsuche_ohne_recht_zeigt_hinweis_statt_fehlerbild', async () => {
    setup((_id, args) => {
      const q = (args.query ?? {}) as Record<string, unknown>;
      if (q.passenger) throw new ApiError(403);
      return page([row(1)]);
    });
    await screen.findByRole('rowheader', { name: 'BK-2026-000001' });
    fireEvent.change(screen.getByLabelText('Fahrgast (Verweis)'), {
      target: { value: '00000000-0000-4000-8000-0000000000aa' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Suchen' }));
    await screen.findByText(/fehlt Ihnen das Recht auf Fahrgastdaten/);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('zeigt_status_fahrzeug_hinweise_und_laedt_weitere_seiten', async () => {
    const { calls } = setup((_id, args) => {
      const q = (args.query ?? {}) as Record<string, unknown>;
      return q.cursor
        ? page([row(3, { vehicle_id: null, vehicle_label: null })])
        : page(
            [
              row(1, { assistance_codes: ['BOARDING_HELP'] }),
              row(2, { state: 'CANCELED', wheelchair_spaces: 1 }),
            ],
            'cur-1',
          );
    });
    const table = await screen.findByRole('table', { name: /Aufträge der berechtigten Dienste/ });
    expect(within(table).getByText('Bestätigt')).toBeTruthy();
    expect(within(table).getByText('Storniert')).toBeTruthy();
    expect(within(table).getByText(/davon 1 Rollstuhl/)).toBeTruthy();
    expect(within(table).getByText('Unterstützungsbedarf: BOARDING_HELP')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Weitere Aufträge laden' }));
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(4));
    expect(within(table).getByText('nicht zugeordnet')).toBeTruthy();
    expect(calls.filter((c) => c.id === 'searchOperatorBookings').at(-1)?.args).toMatchObject({
      query: { cursor: 'cur-1' },
    });
    expect(screen.queryByRole('button', { name: 'Weitere Aufträge laden' })).toBeNull();
  });
});
