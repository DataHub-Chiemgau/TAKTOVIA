import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Driver, OperationId, QualificationRevision } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const DRIVER: Driver = {
  id: '00000000-0000-4000-8000-0000000000d1',
  membership_id: '00000000-0000-4000-8000-0000000000c1',
  status: 'ACTIVE',
  qualification_seq: 2,
  revision: 3,
  hr_fields_visible: true,
  display_name: 'Erika Muster',
  contact_channel: 'PHONE',
  contact_value: '+491701234567',
  created_at: '2026-11-01T08:00:00Z',
  updated_at: '2026-11-01T08:00:00Z',
};

const QUAL: QualificationRevision = {
  id: '00000000-0000-4000-8000-0000000000e1',
  qualification_code: 'PBEF',
  revision: 2,
  status: 'APPROVED',
  valid_from: '2026-01-01T00:00:00Z',
  valid_to: null,
  approved_by: 'user:x',
  approved_at: '2026-01-02T00:00:00Z',
  driver_seq: 2,
  evidence_ref: 'akte-1',
  effective: true,
  valid_at: true,
  created_at: '2026-01-02T00:00:00Z',
};

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'drivers:read', 'drivers:write']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['staff_planning'], scopes }));
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/fahrer']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: Driver[]) => ({ items, next_cursor: null, as_of: '2026-11-12T08:00:00Z' });

describe('UI-11 Flotte: Fahrerliste und Qualifikationspflege', () => {
  it('Leerzustand, Fahrer anlegen und Konflikt der Mitgliedschaft (409)', async () => {
    const { calls } = setup((id) => {
      if (id === 'listDrivers') return page([]);
      if (id === 'createDriver') throw new ApiError(409);
      return undefined;
    });
    expect(await screen.findByText('Noch keine Fahrer in diesem Mandanten')).toBeVisible();
    fireEvent.change(screen.getByLabelText(/Mitgliedschaft/), {
      target: { value: DRIVER.membership_id },
    });
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Erika Muster' } });
    fireEvent.change(screen.getByLabelText('Kontakt'), { target: { value: '+491701234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Fahrer anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createDriver')).toBe(true));
    expect(calls.find((c) => c.id === 'createDriver')?.args['body']).toEqual({
      membership_id: DRIVER.membership_id,
      display_name: 'Erika Muster',
      contact_channel: 'PHONE',
      contact_value: '+491701234567',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('bereits einem Fahrer zugeordnet');
  });

  it('zeigt Qualifikationsrevisionen und legt eine neue Revision mit der Fahrerrevision an', async () => {
    const { calls, container } = setup((id) => {
      if (id === 'listDrivers') return page([DRIVER]);
      if (id === 'listDriverQualifications')
        return { items: [QUAL], next_cursor: null, as_of: 'x', at: 'x' };
      if (id === 'createDriverQualification') return { ...QUAL, revision: 3, driver_revision: 4 };
      if (id === 'getDriver') return { ...DRIVER, revision: 4, qualification_seq: 3 };
      return undefined;
    });
    const table = await screen.findByRole('table', { name: 'Fahrer des aktiven Mandanten' });
    expect(within(table).getByText('Erika Muster')).toBeVisible();
    fireEvent.click(within(table).getByRole('button', { name: /Qualifikationen Erika Muster/ }));
    const quals = await screen.findByRole('table', {
      name: /Qualifikationsrevisionen des gewählten/,
    });
    expect(within(quals).getByText('PBEF')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Qualifikation (Code)'), {
      target: { value: 'ROLLSTUHL' },
    });
    fireEvent.change(screen.getByLabelText('Gültig ab (lokale Zeit)'), {
      target: { value: '2026-11-01T08:00' },
    });
    fireEvent.change(screen.getByLabelText('Entscheidung'), { target: { value: 'PENDING' } });
    fireEvent.click(screen.getByRole('button', { name: 'Revision anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createDriverQualification')).toBe(true));
    const create = calls.find((c) => c.id === 'createDriverQualification');
    expect(create?.args).toMatchObject({ path: { driver_id: DRIVER.id }, revision: 3 });
    expect(create?.args['body']).toMatchObject({
      qualification_code: 'ROLLSTUHL',
      decision: 'PENDING',
      valid_to: null,
    });
    expect(await screen.findByRole('status')).toHaveTextContent(
      'bisherigen Revisionen bleiben lesbar',
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('deaktiviert mit der gelesenen Revision; 412 verständlich', async () => {
    let n = 0;
    const { calls } = setup((id) => {
      if (id === 'listDrivers') return page([DRIVER]);
      if (id === 'updateDriver') {
        if (n++ === 0) return { ...DRIVER, status: 'INACTIVE', revision: 4 };
        throw new ApiError(412);
      }
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: /Deaktivieren Erika Muster/ }));
    await waitFor(() => expect(calls.some((c) => c.id === 'updateDriver')).toBe(true));
    expect(calls.find((c) => c.id === 'updateDriver')?.args).toMatchObject({
      path: { driver_id: DRIVER.id },
      revision: 3,
      body: { display_name: 'Erika Muster', active: false },
    });
    expect(await screen.findByRole('status')).toHaveTextContent('deaktiviert');
    fireEvent.click(await screen.findByRole('button', { name: /Deaktivieren Erika Muster/ }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('zwischenzeitlich geändert'),
    );
  });

  it('Dispositionssicht: HR-Felder nicht sichtbar, nur Lesen', async () => {
    setup(
      (id) => {
        if (id === 'listDrivers')
          return page([
            {
              ...DRIVER,
              hr_fields_visible: false,
              display_name: null,
              contact_channel: null,
              contact_value: null,
            },
          ]);
        return undefined;
      },
      ['me:read', 'drivers:read'],
    );
    const table = await screen.findByRole('table', { name: 'Fahrer des aktiven Mandanten' });
    expect(within(table).getByText('nicht sichtbar')).toBeVisible();
    expect(screen.queryByText('Erika Muster')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Fahrer anlegen' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Deaktivieren/ })).toBeNull();
  });

  it('Fehlerzustand mit Wiederholung und Rechtezustand ohne Daten', async () => {
    let n = 0;
    setup((id) => {
      if (id === 'listDrivers') {
        if (n++ === 0) throw new ApiError(503);
        return page([DRIVER]);
      }
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Erneut versuchen' }));
    expect(await screen.findByText('Erika Muster')).toBeVisible();
  });

  it('ohne Recht: Bereich nicht in der Navigation und ohne Datenabruf', async () => {
    const { calls } = setup(() => page([DRIVER]), ['me:read']);
    expect(await screen.findByText(/Keine Berechtigung/)).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Fahrer und Qualifikationen' })).toBeNull();
    expect(calls.some((c) => c.id === 'listDrivers')).toBe(false);
  });
});
