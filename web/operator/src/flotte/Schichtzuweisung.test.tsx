import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, StaffShift } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const SHIFT: StaffShift = {
  id: '00000000-0000-4000-8000-0000000000f1',
  driver_id: '00000000-0000-4000-8000-0000000000d1',
  vehicle_id: '00000000-0000-4000-8000-0000000000a1',
  starts_at: '2026-12-01T06:00:00Z',
  ends_at: '2026-12-01T14:00:00Z',
  duration_minutes: 480,
  timezone: 'Europe/Berlin',
  state: 'PLANNED',
  roster_revision: 0,
  revision: 1,
  overlap_exception: null,
  started_at: null,
  ended_at: null,
};

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'shifts:read', 'shifts:write']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/schichten']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const fill = async () => {
  fireEvent.change(await screen.findByLabelText('Fahrer (Kennung)'), {
    target: { value: SHIFT.driver_id },
  });
  fireEvent.change(screen.getByLabelText('Fahrzeug (Kennung)'), {
    target: { value: SHIFT.vehicle_id },
  });
  fireEvent.change(screen.getByLabelText('Beginn (lokale Zeit)'), {
    target: { value: '2026-12-01T06:00' },
  });
  fireEvent.change(screen.getByLabelText('Ende (lokale Zeit)'), {
    target: { value: '2026-12-01T14:00' },
  });
};

describe('UI-11 Schichtzuweisung (WP-M3-07)', () => {
  it('zeigt die Liste, weist zu und ist barrierefrei', async () => {
    const { container, calls } = setup((id) =>
      id === 'listStaffShifts'
        ? { items: [SHIFT], next_cursor: null, as_of: '2026-11-12T08:00:00Z' }
        : SHIFT,
    );
    expect(await screen.findByText(/480 Minuten/)).toBeTruthy();
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Schicht zuweisen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createStaffShift')).toBe(true));
    const create = calls.find((c) => c.id === 'createStaffShift');
    expect(create?.args['body']).toMatchObject({
      driver_id: SHIFT.driver_id,
      vehicle_id: SHIFT.vehicle_id,
      timezone: 'Europe/Berlin',
    });
    expect((create?.args['body'] as Record<string, unknown>)['overlap_exception']).toBeUndefined();
    expect(await screen.findByRole('status')).toHaveTextContent('zugewiesen');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt bei Überlappung Ursache und kollidierende Schicht und erlaubt die dokumentierte Ausnahme', async () => {
    let first = true;
    const { calls } = setup((id) => {
      if (id === 'listStaffShifts')
        return { items: [], next_cursor: null, as_of: '2026-11-12T08:00:00Z' };
      if (first) {
        first = false;
        throw new ApiError(409, {
          code: 'CONFLICT',
          status: 409,
          title: 'Konflikt',
          type: 'x',
          correlation_id: '00000000-0000-4000-8000-000000000001',
          detail: `Die Schicht überlappt die Schicht ${SHIFT.id} des Fahrers.`,
          violations: [{ pointer: '/driver_id', code: 'NOT_UNIQUE', message: 'x' }],
        });
      }
      return SHIFT;
    });
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Schicht zuweisen' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Der Fahrer hat in diesem Zeitraum bereits eine Schicht.');
    expect(alert).toHaveTextContent(SHIFT.id);
    fireEvent.change(screen.getByLabelText('Genehmiger'), { target: { value: 'user:leitung' } });
    fireEvent.change(screen.getByLabelText('Grund'), { target: { value: 'Krankheitsvertretung' } });
    fireEvent.click(screen.getByRole('button', { name: 'Als Doppelschicht zuweisen' }));
    await waitFor(() => expect(calls.filter((c) => c.id === 'createStaffShift').length).toBe(2));
    expect(calls.filter((c) => c.id === 'createStaffShift')[1]?.args['body']).toMatchObject({
      overlap_exception: { approved_by: 'user:leitung', reason: 'Krankheitsvertretung' },
    });
  });

  it('entzieht mit der gelesenen Revision und zeigt ohne Schreibrecht kein Formular', async () => {
    const list = { items: [SHIFT], next_cursor: null, as_of: '2026-11-12T08:00:00Z' };
    const { calls } = setup((id) => (id === 'listStaffShifts' ? list : SHIFT));
    fireEvent.click(await screen.findByRole('button', { name: /Zuweisung entziehen/ }));
    await waitFor(() => expect(calls.some((c) => c.id === 'updateStaffShift')).toBe(true));
    expect(calls.find((c) => c.id === 'updateStaffShift')?.args).toMatchObject({
      path: { shift_id: SHIFT.id },
      revision: 1,
      body: { withdrawn: true },
    });
  });

  it('ohne Schreibrecht erscheint nur die Liste', async () => {
    setup(
      () => ({ items: [SHIFT], next_cursor: null, as_of: '2026-11-12T08:00:00Z' }),
      ['me:read', 'shifts:read'],
    );
    expect(await screen.findByText(/480 Minuten/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Schicht zuweisen' })).toBeNull();
  });
});
