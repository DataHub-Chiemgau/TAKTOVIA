import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { coordinateLines } from './Geodaten';

const ID = (n: number) => `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;
const SERVICE = {
  id: ID(1),
  operator_id: ID(9),
  name: 'Rufbus Nord',
  timezone: 'Europe/Berlin',
  config_revision_id: ID(2),
  revision: 1,
};
const PAGE = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-12-01T00:00:00Z' });
const AREA = {
  id: ID(10),
  revision_id: ID(11),
  zone_role: 'BOTH',
  service_id: ID(1),
  revision: 2,
  area: {
    type: 'MultiPolygon',
    coordinates: [
      [
        [
          [13.7, 51.0],
          [13.8, 51.0],
          [13.8, 51.1],
          [13.7, 51.0],
        ],
      ],
    ],
  },
  valid_from: '2026-12-01T00:00:00Z',
  valid_to: null,
};
const STOP = {
  id: ID(20),
  stop_id: ID(21),
  revision: 3,
  name: 'Marktplatz',
  point: { latitude: 51.05, longitude: 13.74 },
  accessibility: { schema_version: '1.0', step_free: 'YES', wheelchair_boarding: 'UNVERIFIED' },
  traffic_products: ['AREA_ON_DEMAND'],
  active: true,
  approved_by: 'user:x',
  valid_from: '2026-01-01T00:00:00Z',
  valid_to: null,
};
const EMPTY_HOURS = {
  service_id: ID(1),
  revision: 0,
  traffic_product: null,
  timezone: null,
  weekly_hours: null,
  exceptions: null,
  valid_from: null,
  valid_to: null,
  reason: null,
  created_by: null,
  created_at: null,
};
const EMPTY_RULE = {
  service_id: ID(1),
  revision: 0,
  destination_stop_ids: [],
  valid_from: null,
  valid_to: null,
  reason: null,
  created_by: null,
  created_at: null,
};

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (args: Record<string, unknown>) => unknown;

function setup(
  handlers: Partial<Record<OperationId, Handler>>,
  scopes = [
    'me:read',
    'config:read',
    'config:write',
    'services:read',
    'services:write',
    'geo:read',
    'geo:write',
  ],
) {
  const calls: Call[] = [];
  const base: Partial<Record<OperationId, Handler>> = {
    listServices: () => PAGE([SERVICE]),
    getServiceOperatingHours: () => EMPTY_HOURS,
    listServiceAreas: () => PAGE([]),
    listStops: () => PAGE([]),
    getServiceRelationRule: () => EMPTY_RULE,
  };
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['tenant_admin'], scopes }));
      const h = handlers[id] ?? base[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/geodaten']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const problem = (
  status: number,
  code: string,
  violations: { pointer: string; code: string; message: string }[] = [],
) =>
  new ApiError(status, {
    type: 'about:blank',
    title: 'x',
    status,
    code,
    correlation_id: ID(99),
    violations,
  } as never);

describe('UI-17 Geodaten (WP-M2-03 bis -06)', () => {
  it('betriebszeiten_leer_zustand_und_speichern_mit_if_match_0', async () => {
    const { calls, container } = setup({
      putServiceOperatingHours: () => ({ ...EMPTY_HOURS, revision: 1 }),
    });
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Gebiete, Haltepunkte, Betriebszeiten',
      }),
    ).toBeInTheDocument();
    expect(await screen.findByText('Keine Betriebszeit hinterlegt')).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
    fireEvent.change(
      screen.getByLabelText('Grund der Änderung', {
        selector: 'form[aria-label="Betriebszeit speichern"] input',
      }),
      {
        target: { value: 'Pilotstart' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Betriebszeit speichern' }));
    expect(await screen.findByText(/als neue Dienstversion gespeichert/)).toBeInTheDocument();
    const put = calls.find((c) => c.id === 'putServiceOperatingHours');
    expect(put?.args).toMatchObject({
      path: { service_id: ID(1) },
      revision: 0,
      body: {
        traffic_product: 'AREA_ON_DEMAND',
        reason: 'Pilotstart',
        weekly_hours: [{ from: '06:00', to: '22:00' }],
      },
    });
    expect(JSON.stringify(calls)).not.toMatch(/tenant_id/);
  });

  it('betriebszeiten_konkurrierende_version_412_und_validierungsfehler_422', async () => {
    let n = 0;
    setup({
      getServiceOperatingHours: () => ({
        ...EMPTY_HOURS,
        revision: 2,
        traffic_product: 'AREA_ON_DEMAND',
        timezone: 'Europe/Berlin',
        weekly_hours: [{ weekdays: ['MON', 'TUE'], from: '06:00', to: '22:00' }],
        exceptions: [],
      }),
      putServiceOperatingHours: () =>
        ++n === 1
          ? problem(412, 'REVISION_MISMATCH')
          : problem(422, 'VALIDATION_FAILED', [
              { pointer: '/weekly_hours/0/from', code: 'PATTERN_MISMATCH', message: 'x' },
            ]),
    });
    expect(await screen.findByRole('table', { name: 'Wochenschema' })).toBeInTheDocument();
    expect(screen.getByText('Mo, Di')).toBeInTheDocument();
    fireEvent.change(
      screen.getByLabelText('Grund der Änderung', {
        selector: 'form[aria-label="Betriebszeit speichern"] input',
      }),
      {
        target: { value: 'Änderung' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Betriebszeit speichern' }));
    expect(await screen.findByText(/Konkurrierende Version/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Betriebszeit speichern' }));
    expect(await screen.findByText(/Die Eingabe wurde abgewiesen \(422\)/)).toBeInTheDocument();
    expect(screen.getByText('/weekly_hours/0/from')).toBeInTheDocument();
  });

  it('bediengebiet_koordinatenliste_neben_der_karte_ungueltige_geometrie_und_revision', async () => {
    let n = 0;
    const { calls } = setup({
      listServiceAreas: () => PAGE([AREA]),
      updateServiceArea: () =>
        ++n === 1
          ? problem(422, 'VALIDATION_FAILED', [
              {
                pointer: '/area',
                code: 'INVALID_VALUE',
                message: 'Die Geometrie überschneidet sich selbst',
              },
            ])
          : AREA,
    });
    expect(await screen.findByText(/Revision 2, Start und Ziel/)).toBeInTheDocument();
    expect(coordinateLines(AREA.area as never)).toEqual([
      '13.7, 51',
      '13.8, 51',
      '13.8, 51.1',
      '13.7, 51',
    ]);
    expect(screen.getByText('Koordinatenliste')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Revision bearbeiten' }));
    fireEvent.change(screen.getByLabelText('Zonenrolle'), { target: { value: 'ORIGIN' } });
    fireEvent.click(screen.getByRole('button', { name: 'Neue Revision speichern' }));
    expect(await screen.findByText(/Ungültige Geometrie/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Neue Revision speichern' }));
    expect(await screen.findByText('Bediengebiet wurde gespeichert.')).toBeInTheDocument();
    const upd = calls.filter((c) => c.id === 'updateServiceArea');
    expect(upd[1]?.args).toMatchObject({
      path: { area_id: ID(10) },
      revision: 2,
      body: { zone_role: 'ORIGIN', area: { type: 'MultiPolygon' } },
    });
  });

  it('haltepunkte_leer_anlegen_und_deaktivieren_als_neue_revision', async () => {
    let stops = [] as unknown[];
    const { calls } = setup({
      listStops: () => PAGE(stops),
      createStop: () => {
        stops = [STOP];
        return STOP;
      },
      updateStop: () => ({ ...STOP, active: false, revision: 4 }),
    });
    expect(await screen.findByText('Noch keine Haltepunkte')).toBeInTheDocument();
    fireEvent.change(
      screen.getByLabelText('Name', { selector: 'form[aria-label="Haltepunkt anlegen"] input' }),
      { target: { value: 'Marktplatz' } },
    );
    fireEvent.change(screen.getByLabelText('Breite'), { target: { value: '51.05' } });
    fireEvent.change(screen.getByLabelText('Länge'), { target: { value: '13.74' } });
    fireEvent.click(screen.getByRole('button', { name: 'Haltepunkt anlegen' }));
    expect(await screen.findByText('Haltepunkt wurde angelegt.')).toBeInTheDocument();
    const create = calls.find((c) => c.id === 'createStop');
    expect(create?.args).toMatchObject({
      body: {
        name: 'Marktplatz',
        active: true,
        traffic_products: ['AREA_ON_DEMAND'],
        accessibility: { schema_version: '1.0', wheelchair_boarding: 'UNVERIFIED' },
      },
    });
    const row = (await screen.findByRole('table', { name: 'Haltepunkte' })).querySelector(
      'tbody tr',
    ) as HTMLElement;
    expect(within(row).getByText('aktiv')).toBeInTheDocument();
    fireEvent.click(within(row).getByRole('button', { name: 'Deaktivieren Marktplatz' }));
    expect(await screen.findByText(/wurde deaktiviert \(neue Revision\)/)).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'updateStop')?.args).toMatchObject({
      path: { stop_id: ID(21) },
      revision: 3,
      body: { active: false },
    });
  });

  it('zielpunkte_speichern_und_relationspruefung_zeigt_den_grund', async () => {
    const { calls } = setup({
      listStops: () => PAGE([STOP]),
      putServiceRelationRule: () => ({
        ...EMPTY_RULE,
        revision: 1,
        destination_stop_ids: [STOP.stop_id],
      }),
      checkServiceRelation: () => ({
        service_id: ID(1),
        at: '2026-12-01T00:00:00Z',
        admissible: false,
        reason: 'RULE_FORBIDDEN',
        detail: 'Das Ziel ist kein erlaubter Zielpunkt',
      }),
    });
    expect(await screen.findByText(/Keine Einschränkung durch Zielpunkte/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('checkbox', { name: /Marktplatz/ }));
    fireEvent.change(
      screen.getByLabelText('Grund der Änderung', {
        selector: 'form[aria-label="Zielpunkte speichern"] input',
      }),
      { target: { value: 'Nur Marktplatz' } },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Zielpunkte speichern' }));
    expect(await screen.findByText('Erlaubte Zielpunkte wurden gespeichert.')).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'putServiceRelationRule')?.args).toMatchObject({
      revision: 0,
      body: { destination_stop_ids: [STOP.stop_id], reason: 'Nur Marktplatz' },
    });
    for (const [label, v] of [
      ['Start Breite', '51.05'],
      ['Start Länge', '13.05'],
      ['Ziel Breite', '51.05'],
      ['Ziel Länge', '14.05'],
    ] as const)
      fireEvent.change(screen.getByLabelText(label), { target: { value: v } });
    fireEvent.click(screen.getByRole('button', { name: 'Relation prüfen' }));
    expect(await screen.findByText(/Relation abgelehnt: Regelverbot/)).toBeInTheDocument();
  });

  it('fehlendes_recht_403_zeigt_keine_daten_und_fehler_mit_erneutem_laden', async () => {
    let n = 0;
    setup({ listServices: () => (++n === 1 ? new ApiError(503) : new ApiError(403)) });
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
    await waitFor(() =>
      expect(
        screen
          .getAllByTestId('state-view')
          .some((e) => e.getAttribute('data-kind') === 'forbidden'),
      ).toBe(true),
    );
    expect(screen.queryByText('Betriebszeiten')).not.toBeInTheDocument();
  });
});
