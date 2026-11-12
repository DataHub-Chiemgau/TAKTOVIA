import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@taktovia/api-client';
import type { OperationId, OperatorVehicle, VehicleOperationalState } from '@taktovia/api-client';
import { runAxe } from '@taktovia/design-system/testing';
import { VehicleStatus, isDispatchable } from './VehicleStatus';

const vehicle = (n: number, state: VehicleOperationalState): OperatorVehicle => ({
  vehicle_id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  vehicle_label: `KA-${100 + n}`,
  operational_state: state,
  safety_revision: 1,
  release_valid_until: null,
  missing_requirements: [],
  active_blocks: [],
  position: null,
  position_measured_at: null,
  position_age_seconds: null,
  position_stale: true,
  open_defect_count: 0,
  current_run_id: null,
  revision: 1,
});

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler) {
  const call = vi.fn((id: OperationId, args: Record<string, unknown>) => {
    try {
      return Promise.resolve(handler(id, args));
    } catch (e) {
      return Promise.reject(e);
    }
  });
  const api = { call } as unknown as Parameters<typeof VehicleStatus>[0]['api'];
  const view = render(<VehicleStatus api={api} />);
  return { ...view, call };
}

const page = (items: OperatorVehicle[]) => ({
  items,
  next_cursor: null,
  as_of: '2026-11-12T08:00:00Z',
});

describe('UI-07 Status je Fahrzeug (WP-M3-31, LH-6.6-DRV-007)', () => {
  it('ui07_zeigt_alle_sechs_status', async () => {
    const states: [VehicleOperationalState, string, string][] = [
      ['AVAILABLE', 'Verfügbar', 'möglich'],
      ['EN_ROUTE', 'Anfahrt', 'möglich'],
      ['WAITING', 'Wartezeit', 'möglich'],
      ['BREAK', 'Pause', 'nicht möglich'],
      ['DISRUPTED', 'Störung', 'nicht möglich'],
      ['ENDED', 'Ende', 'nicht möglich'],
    ];
    const { call, container } = setup(() => page(states.map(([s], i) => vehicle(i + 1, s))));
    const table = await screen.findByRole('table', { name: 'Dienststatus der Fahrzeuge' });
    expect(call).toHaveBeenCalledWith('listOperatorVehicles', { query: { limit: 100 } });
    states.forEach(([state, label, dispatch], i) => {
      const row = within(table).getByRole('row', { name: new RegExp(`KA-${101 + i}`) });
      expect(within(row).getByText(label)).toHaveAttribute('data-status', state);
      expect(within(row).getByText(dispatch)).toBeInTheDocument();
    });
    // die Disponierbarkeit der Anzeige entspricht der Originalabnahme: nur Verfügbarkeit, Anfahrt, Wartezeit
    expect(states.filter(([s]) => isDispatchable(s)).map(([s]) => s)).toEqual([
      'AVAILABLE',
      'EN_ROUTE',
      'WAITING',
    ]);
    // Hinweis auf die Wirkung offline gemeldeter Störung und Ende
    expect(screen.getByText(/erst ab Serverempfang/)).toBeInTheDocument();
    expect(await runAxe(container)).toEqual([]);
  });

  it('Leerzustand und Fehlerzustand mit Wiederholung', async () => {
    let fail = true;
    const { call } = setup(() => {
      if (fail) throw new ApiError(503, undefined);
      return page([]);
    });
    await screen.findByRole('button', { name: 'Erneut versuchen' });
    fail = false;
    screen.getByRole('button', { name: 'Erneut versuchen' }).click();
    await waitFor(() => expect(screen.getByText('Keine Fahrzeuge')).toBeInTheDocument());
    expect(call).toHaveBeenCalledTimes(2);
  });
});
