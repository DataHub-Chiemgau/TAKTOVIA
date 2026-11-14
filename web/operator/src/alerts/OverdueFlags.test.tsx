import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  OperationId,
  OperatorFlagItem,
  OperatorVehicle,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const VEHICLE = '00000000-0000-4000-8000-0000000000f1';
const SERVICE = '00000000-0000-4000-8000-00000000aaaa';

const flag = (n: number, over: Partial<OperatorFlagItem>): OperatorFlagItem => ({
  id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  kind: 'OVERDUE',
  cause: 'PICKUP_OVERDUE',
  state: 'OPEN',
  service_id: SERVICE,
  vehicle_id: VEHICLE,
  affected_count: 3,
  opened_at: '2026-12-01T09:10:00Z',
  resolved_at: null,
  threshold_min: 5,
  revision: 1,
  ...over,
});

const vehicle: OperatorVehicle = {
  vehicle_id: VEHICLE,
  vehicle_label: 'KA-101',
  operational_state: 'AVAILABLE',
  safety_revision: 1,
  release_valid_until: '2026-12-01T16:00:00Z',
  missing_requirements: [],
  active_blocks: [],
  position: { latitude: 49, longitude: 8 },
  position_measured_at: '2026-12-01T08:00:00Z',
  position_received_at: '2026-12-01T08:00:01Z',
  position_age_seconds: 4,
  position_stale: false,
  open_defect_count: 0,
  inspection_status: 'PENDING',
  inspection_status_revision: 1,
  current_run_id: null,
  revision: 1,
  service_ids: [SERVICE],
  unavailable_dimensions: [],
  open_alert_count: 0,
  orders: [],
  flags: [
    {
      id: 'f-1',
      kind: 'OVERDUE',
      cause: 'PICKUP_OVERDUE',
      affected_count: 3,
      opened_at: '2026-12-01T09:10:00Z',
      threshold_min: 5,
    },
    {
      id: 'f-2',
      kind: 'INFO_PENDING',
      cause: 'POSITION_STALE',
      affected_count: 3,
      opened_at: '2026-12-01T09:00:00Z',
      threshold_min: 15,
    },
  ],
};

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(path: string, handler: Handler) {
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
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
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}

const page = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-12-01T09:20:00Z' });

describe('UI-07 Überfälligkeit und ausstehende Informationen (WP-M3-42, LH-6.7-OPS-006)', () => {
  it('verspaetung_und_information_ausstehend_getrennt_in_liste_und_detail_leerzustand', async () => {
    // Fahrzeugliste und Detail: beide Arten getrennt, mit der Zahl der Betroffenen
    const view = setup('/leitstelle/fahrzeuge', (id) => {
      if (id === 'listOperatorVehicles') return page([vehicle]);
      throw new ApiError(404);
    });
    const table = await screen.findByRole('table', { name: /Fahrzeuge der berechtigten Dienste/ });
    expect(within(table).getByText('Überfällig: Abholung überfällig (3 betroffen)')).toBeTruthy();
    expect(
      within(table).getByText('Information ausstehend: Standort veraltet (3 betroffen)'),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Details zu KA-101' }));
    const panel = await screen.findByRole('complementary', { name: 'Details zu KA-101' });
    const notes = within(panel).getByRole('list', { name: 'Hinweise' });
    expect(within(notes).getByText(/^Überfällig: /)).toBeTruthy();
    expect(within(notes).getByText(/^Information ausstehend: /)).toBeTruthy();
    view.unmount();

    // Arbeitsliste: zwei getrennte Abschnitte, Betroffenenzahl, ohne Namen
    const second = setup('/leitstelle/hinweise', (id) => {
      if (id !== 'listOperatorFlags') throw new ApiError(404);
      return page([
        flag(1, {}),
        flag(2, {
          kind: 'INFO_PENDING',
          cause: 'PLAN_ACK_MISSING',
          affected_count: 2,
          threshold_min: 5,
        }),
      ]);
    });
    const overdue = await screen.findByRole('region', { name: 'Überfälligkeit (Verspätung)' });
    const info = screen.getByRole('region', { name: 'Information ausstehend' });
    expect(within(overdue).getByText('Abholung überfällig')).toBeTruthy();
    expect(within(overdue).queryByText('Fahrerbestätigung der Planänderung fehlt')).toBeNull();
    expect(within(info).getByText('Fahrerbestätigung der Planänderung fehlt')).toBeTruthy();
    expect(within(overdue).getByRole('cell', { name: '3' })).toBeTruthy();
    expect(within(info).getByRole('cell', { name: '2' })).toBeTruthy();
    const violations = await runAxe(second.container);
    expect(violations, formatViolations(violations)).toEqual([]);
    second.unmount();

    // Leerzustand: keine offenen Hinweise ist ein ausdrücklicher Zustand, kein Fehler
    setup('/leitstelle/hinweise', () => page<OperatorFlagItem>([]));
    await screen.findByText('Keine offenen Hinweise');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(/nichts überfällig und keine Information ausstehend/)).toBeTruthy();
  });
});
