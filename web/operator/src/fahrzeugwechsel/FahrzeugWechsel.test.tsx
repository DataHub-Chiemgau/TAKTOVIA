import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, ShiftVehicleSwitch } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { App } from '../App';
import { switchErrorText } from './FahrzeugWechsel';

const SHIFT = '00000000-0000-4000-8000-0000000000a1';
const OLD = '00000000-0000-4000-8000-0000000000a2';
const NEW = '00000000-0000-4000-8000-0000000000a3';

const RESULT: ShiftVehicleSwitch = {
  id: '00000000-0000-4000-8000-0000000000b1',
  shift_id: SHIFT,
  from_vehicle_id: OLD,
  to_vehicle_id: NEW,
  reason_code: 'BREAKDOWN',
  note: null,
  actor_role: 'DISPATCH',
  revoked_releases: 1,
  inspection_required: true,
  created_at: '2026-11-13T08:00:00Z',
};

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(scopes: string[], failWith?: ApiError) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      if (id === 'createShiftVehicleSwitch')
        return failWith ? Promise.reject(failWith) : Promise.resolve(RESULT);
      if (id === 'listShiftVehicleSwitches') return Promise.resolve({ items: [RESULT] });
      return Promise.reject(new Error('unerwartet ' + id));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/fahrzeugwechsel']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const fill = async () => {
  fireEvent.change(await screen.findByLabelText('Schicht-ID'), { target: { value: SHIFT } });
  fireEvent.change(screen.getByLabelText('Aktuelles Fahrzeug (ID)'), { target: { value: OLD } });
  fireEvent.change(screen.getByLabelText('Ersatzfahrzeug (ID)'), { target: { value: NEW } });
};

describe('UI-07 Fahrzeugwechsel', () => {
  it('wechsel_sendet_bedingung_und_zeigt_nicht_disponierbar_bis_pruefung', async () => {
    const { calls, container } = setup(['shifts:write', 'fleet:switch']);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug wechseln' }));
    await screen.findByText(/nicht disponierbar, bis seine Prüfung bestätigt ist/);
    const call = calls.find((c) => c.id === 'createShiftVehicleSwitch');
    expect(call?.args).toMatchObject({
      path: { shift_id: SHIFT },
      body: { from_vehicle_id: OLD, new_vehicle_id: NEW, reason_code: 'BREAKDOWN' },
    });
    expect(screen.getByText(/1 Einsatzfreigabe\(n\) des bisherigen Fahrzeugs/)).toBeTruthy();
    await screen.findByRole('table', { name: 'Wechselhistorie der Schicht' });
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('ohne_recht_keine_eingabe_und_fehler_werden_neutral_gemeldet', async () => {
    const denied = setup(['fleet:read']);
    expect(await screen.findByText('Keine Berechtigung')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Fahrzeug wechseln' })).toBeNull();
    denied.unmount();
    const blocked = setup(
      ['shifts:write', 'fleet:switch'],
      new ApiError(409, {
        type: 'https://api.taktovia.example/problems/conflict',
        title: 'Konflikt',
        status: 409,
        code: 'CONFLICT',
        detail: 'VEHICLE_BLOCKED: Das Ersatzfahrzeug ist gesperrt.',
        correlation_id: '00000000-0000-4000-8000-0000000000c1',
      }),
    );
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug wechseln' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/gesperrt/));
    expect(blocked.calls.some((c) => c.id === 'listShiftVehicleSwitches')).toBe(false);
  });

  it('fehlertexte_unterscheiden_gesperrt_konflikt_und_eingabe', () => {
    expect(switchErrorText(409, 'VEHICLE_BLOCKED: x')).toMatch(/gesperrt/);
    expect(switchErrorText(409)).toMatch(/nicht möglich/);
    expect(switchErrorText(422)).toMatch(/Eingaben/);
    expect(switchErrorText(404)).toMatch(/nicht gefunden/);
  });
});
