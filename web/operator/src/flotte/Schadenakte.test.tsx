import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  Defect,
  DefectRevision,
  OperationId,
  VehicleMaster,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const VEHICLE: VehicleMaster = {
  id: '00000000-0000-4000-8000-0000000000a1',
  vehicle_label: 'KA-100',
  vehicle_type: 'PKW',
  operator_organization_id: null,
  capacity: { seats: 6, wheelchair: 0 },
  equipment: [],
  active: true,
  dispatch_blocked: false,
  dispatchable: true,
  safety_revision: 1,
  has_capacity_layout: false,
  revision: 1,
  created_at: '2026-11-01T08:00:00Z',
  updated_at: '2026-11-01T08:00:00Z',
};

const DEFECT: Defect = {
  id: '00000000-0000-4000-8000-0000000000d1',
  case_number: 'SCH-2026-000001',
  vehicle_id: VEHICLE.id,
  reported_by: '00000000-0000-4000-8000-0000000000c1',
  reported_at: '2026-11-11T08:00:00Z',
  occurred_at: '2026-11-11T07:30:00Z',
  category_code: 'BODY',
  kind: 'DENT',
  vehicle_area: 'LEFT_SIDE',
  observed_effect: 'COSMETIC_ONLY',
  severity: null,
  status: 'NEW',
  assignee_ref: null,
  blocks_vehicle: false,
  evidence_ids: [],
  evidence_pending: 1,
  content_version: 2,
  description: 'Delle in der Seitentür',
  revision: 1,
};

const rev = (version: number, description: string, reason: string): DefectRevision => ({
  version,
  author: 'user:00000000-0000-4000-8000-0000000000c1',
  change_reason: reason,
  created_at: `2026-11-11T0${7 + version}:00:00Z`,
  content: {
    occurred_at: '2026-11-11T07:30:00Z',
    location_kind: null,
    location_value: null,
    category_code: 'BODY',
    kind: 'DENT',
    vehicle_area: 'LEFT_SIDE',
    observed_effect: 'COSMETIC_ONLY',
    description,
    source_inspection_id: null,
    source_step_code: null,
  },
  evidence: [],
});

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(
  handler: Handler,
  scopes = [
    'me:read',
    'defects:triage',
    'defects:read',
    'defects:write',
    'defects:state',
    'fleet:read',
  ],
) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      if (id === 'listVehicleMasters')
        return Promise.resolve({
          items: [VEHICLE],
          next_cursor: null,
          as_of: '2026-11-11T08:00:00Z',
        });
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/schaeden']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: Defect[]) => ({ items, next_cursor: null, as_of: '2026-11-11T08:00:00Z' });

describe('UI-11 Schadenakte: Erfassung, Übergänge, Nachtrag, Versionsverlauf', () => {
  it('zeigt den Leerzustand und Feldfehler der API (422) bei der Erfassung', async () => {
    const { calls } = setup((id) => {
      if (id === 'listDefects') return page([]);
      if (id === 'createDefect')
        throw new ApiError(422, {
          type: 'x',
          title: 'x',
          status: 422,
          code: 'VALIDATION_FAILED',
          correlation_id: 'c',
          violations: [{ pointer: '/description', code: 'REQUIRED', message: 'm' }],
        });
      return undefined;
    });
    expect(await screen.findByText('Noch keine Schadenakten in diesem Mandanten')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Fahrzeug'), { target: { value: VEHICLE.id } });
    fireEvent.change(screen.getByLabelText('Ereigniszeit'), {
      target: { value: '2026-11-11T07:30' },
    });
    fireEvent.change(screen.getByLabelText('Kategorie'), { target: { value: 'BODY' } });
    fireEvent.click(screen.getByRole('button', { name: 'Schaden melden' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createDefect')).toBe(true));
    expect(calls.find((c) => c.id === 'createDefect')?.args['body']).toMatchObject({
      vehicle_id: VEHICLE.id,
      category_code: 'BODY',
      description: '',
    });
    expect((await screen.findAllByText(/Dieses Feld ist erforderlich/)).length).toBeGreaterThan(0);
    expect(screen.queryByText(/wurde angelegt/)).toBeNull();
  });

  it('bietet nur zulässige Folgeaktionen an und führt den Übergang mit der gelesenen Revision aus', async () => {
    const { calls, container } = setup((id) => {
      if (id === 'listDefects') return page([DEFECT]);
      if (id === 'getDefect') return DEFECT;
      if (id === 'transitionDefect') return { ...DEFECT, status: 'REVIEWED', revision: 2 };
      return undefined;
    });
    const table = await screen.findByRole('table', { name: 'Schadenakten des aktiven Mandanten' });
    expect(within(table).getByText('SCH-2026-000001')).toBeVisible();
    fireEvent.click(within(table).getByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    expect(
      await screen.findByRole('heading', { name: 'Schadenakte SCH-2026-000001' }),
    ).toBeVisible();
    // Status neu: nur „geprüft“ und „verwerfen“, nie „abschließen“ oder „Reparatur“
    expect(screen.getByRole('button', { name: 'Als geprüft markieren' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Verwerfen' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /abschließen/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Reparatur/ })).toBeNull();
    expect(screen.getByText('1 in Prüfung', { exact: false })).toBeVisible();
    expect(screen.queryByText('Versionsverlauf')).toBeNull(); // ohne Prüferrecht kein Verlauf
    fireEvent.click(screen.getByRole('button', { name: 'Als geprüft markieren' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'transitionDefect')).toBe(true));
    expect(calls.find((c) => c.id === 'transitionDefect')?.args).toMatchObject({
      path: { defect_id: DEFECT.id },
      body: { to: 'REVIEWED' },
      revision: 1,
    });
    expect(await screen.findByRole('status')).toHaveTextContent('geprüft');
    expect(await screen.findByRole('button', { name: 'In Bearbeitung nehmen' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Als geprüft markieren' })).toBeNull();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt unzulässigen Übergang (409) und veraltete Revision (412) verständlich; Wiedereröffnung verlangt Begründung', async () => {
    let n = 0;
    const { calls } = setup((id) => {
      if (id === 'listDefects') return page([{ ...DEFECT, status: 'CLOSED' }]);
      if (id === 'getDefect') return { ...DEFECT, status: 'CLOSED' };
      if (id === 'transitionDefect') throw new ApiError(n++ === 0 ? 409 : 412);
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    // Endzustand: nur die Wiedereröffnung
    expect(await screen.findByRole('button', { name: 'Wiedereröffnen' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Verwerfen' })).toBeNull();
    fireEvent.change(screen.getByLabelText(/Begründung/), { target: { value: 'Neuer Befund' } });
    fireEvent.click(screen.getByRole('button', { name: 'Wiedereröffnen' }));
    expect(await screen.findByRole('status')).toHaveTextContent('nicht zulässig');
    expect(calls.find((c) => c.id === 'transitionDefect')?.args['body']).toEqual({
      to: 'REVIEWED',
      reason: 'Neuer Befund',
      reopen: true,
    });
    fireEvent.click(screen.getByRole('button', { name: 'Wiedereröffnen' }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('zwischenzeitlich geändert'),
    );
  });

  it('Prüfer sehen den Versionsverlauf mit Vergleich; ein Nachtrag verlangt den Änderungsgrund (422 am Feld)', async () => {
    const versions = [
      rev(1, 'Delle', 'Erstmeldung'),
      rev(2, 'Delle in der Seitentür', 'Beschreibung präzisiert'),
    ];
    let first = true;
    const { calls, container } = setup(
      (id) => {
        if (id === 'listDefects') return page([DEFECT]);
        if (id === 'getDefect') return DEFECT;
        if (id === 'listDefectHistory')
          return { items: versions, next_cursor: null, as_of: '2026-11-11T09:00:00Z' };
        if (id === 'amendDefect') {
          if (first) {
            first = false;
            throw new ApiError(422, {
              type: 'x',
              title: 'x',
              status: 422,
              code: 'VALIDATION_FAILED',
              correlation_id: 'c',
              violations: [{ pointer: '/change_reason', code: 'REQUIRED', message: 'm' }],
            });
          }
          return { ...DEFECT, content_version: 3 };
        }
        return undefined;
      },
      [
        'me:read',
        'defects:triage',
        'defects:read',
        'defects:write',
        'defects:state',
        'defects:history',
        'fleet:read',
      ],
    );
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    const verlauf = await screen.findByRole('table', { name: 'Versionen der Meldung' });
    expect(within(verlauf).getByText('Erstmeldung')).toBeVisible();
    expect(within(verlauf).getByText('Beschreibung präzisiert')).toBeVisible();
    // Vergleich Version 1 gegen 2: Änderung ist als Text („geändert“), nicht nur farblich gekennzeichnet
    const vergleich = await screen.findByRole('table', { name: 'Vergleich der Versionen 1 und 2' });
    const row = within(vergleich).getByText('Beschreibung').closest('tr') as HTMLElement;
    expect(within(row).getByText('Delle')).toBeVisible();
    expect(within(row).getByText('geändert')).toBeVisible();
    expect(
      within(within(vergleich).getByText('Kategorie').closest('tr') as HTMLElement).getByText(
        'unverändert',
      ),
    ).toBeVisible();
    // Nachtrag ohne Grund: Feldfehler vom Server
    fireEvent.change(screen.getByLabelText('Beschreibung (neu)'), {
      target: { value: 'Delle hinten' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Nachtrag speichern' }));
    expect((await screen.findAllByText(/Dieses Feld ist erforderlich/)).length).toBeGreaterThan(0);
    fireEvent.change(screen.getByLabelText('Änderungsgrund'), {
      target: { value: 'Ort präzisiert' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Nachtrag speichern' }));
    await waitFor(() => expect(calls.filter((c) => c.id === 'amendDefect')).toHaveLength(2));
    expect(calls.filter((c) => c.id === 'amendDefect')[1]?.args['body']).toEqual({
      change_reason: 'Ort präzisiert',
      description: 'Delle hinten',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Version 3');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ohne Schreib- und Statusrecht gibt es weder Formulare noch Folgeaktionen (Rechtezustand)', async () => {
    setup(
      (id) => {
        if (id === 'listDefects') return page([DEFECT]);
        if (id === 'getDefect') return DEFECT;
        return undefined;
      },
      ['me:read', 'defects:triage', 'defects:read'],
    );
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    expect(
      await screen.findByRole('heading', { name: 'Schadenakte SCH-2026-000001' }),
    ).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Schaden erfassen' })).toBeNull();
    expect(screen.queryByRole('group', { name: 'Folgeaktionen' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Nachtrag speichern' })).toBeNull();
  });
});
