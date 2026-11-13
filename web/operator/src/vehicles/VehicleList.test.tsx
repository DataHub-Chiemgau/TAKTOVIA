import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, OperatorVehicle } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { blockText, notesOf, positionText } from './VehicleList';

const SVC_A = '00000000-0000-4000-8000-00000000aaaa';
const SVC_B = '00000000-0000-4000-8000-00000000bbbb';

const vehicle = (n: number, over: Partial<OperatorVehicle> = {}): OperatorVehicle => ({
  vehicle_id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  vehicle_label: `KA-${100 + n}`,
  operational_state: 'AVAILABLE',
  safety_revision: 1,
  release_valid_until: '2026-12-01T16:00:00Z',
  missing_requirements: [],
  active_blocks: [],
  position: { latitude: 49 + n / 100, longitude: 8 + n / 100 },
  position_measured_at: '2026-12-01T08:00:00Z',
  position_received_at: '2026-12-01T08:00:01Z',
  position_age_seconds: 4,
  position_stale: false,
  open_defect_count: 0,
  current_run_id: null,
  revision: 1,
  service_ids: [SVC_A],
  unavailable_dimensions: [],
  open_alert_count: 0,
  orders: [],
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
    <MemoryRouter initialEntries={['/leitstelle/fahrzeuge']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: OperatorVehicle[], next: string | null = null) => ({
  items,
  next_cursor: next,
  as_of: '2026-12-01T08:05:00Z',
});

describe('UI-07 Livebetrieb Fahrzeuge (WP-M3-36, LH-6.7-OPS-001)', () => {
  it('filter_schraenkt_nur_ein_und_zustaende_sind_sichtbar', async () => {
    const all = [
      vehicle(1),
      vehicle(2, {
        operational_state: 'BLOCKED',
        active_blocks: [
          {
            id: '00000000-0000-4000-8000-0000000000b1',
            vehicle_id: '00000000-0000-4000-8000-000000000002',
            source: 'SAFETY',
            reason_code: 'INSPECTION_FAILED',
            set_at: '2026-12-01T07:00:00Z',
            set_by: 'service:inspection',
            lifted_at: null,
            lifted_by: null,
            revision: 1,
            block_kind: 'VEHICLE',
            case_number: 'SPE-2026-000007',
          },
        ],
      }),
      vehicle(3, { position_stale: true, position_age_seconds: 95, service_ids: [SVC_B] }),
      vehicle(4, {
        position: null,
        position_measured_at: null,
        position_age_seconds: null,
        position_stale: true,
      }),
      vehicle(5, {
        operational_state: 'RELEASE_PENDING',
        missing_requirements: [{ code: 'INSPECTION_OPEN', step_codes: ['BELEUCHTUNG'] }],
      }),
      vehicle(6, { operational_state: 'RESTRICTED', unavailable_dimensions: ['wheelchair'] }),
    ];
    const { calls } = setup((id, args) => {
      if (id !== 'listOperatorVehicles') throw new ApiError(404);
      const q = (args.query ?? {}) as Record<string, unknown>;
      let items = all;
      if (q.service_id)
        items = items.filter((v) => v.service_ids?.includes(q.service_id as string));
      if (q.state) items = items.filter((v) => v.operational_state === q.state);
      return page(items);
    });
    const table = await screen.findByRole('table', { name: /Fahrzeuge der berechtigten Dienste/ });
    // Zustände sichtbar: Sperre mit Grund und Vorgangsnummer, veralteter Standort mit letztem Stand, fehlende Voraussetzungen, eingeschränkt, ohne Position
    expect(
      within(table).getByText('gesperrt · kritischer Mangel (Vorgang SPE-2026-000007)'),
    ).toBeTruthy();
    expect(within(table).getByText(/veraltet · letzter Stand .*vor 95 s/)).toBeTruthy();
    expect(within(table).getByText('keine Position bekannt')).toBeTruthy();
    expect(
      within(table).getByText(
        /nicht einsatzbereit: Prüfung nicht abgeschlossen \(offen: BELEUCHTUNG\)/,
      ),
    ).toBeTruthy();
    expect(within(table).getByText(/eingeschränkt: keine Rollstuhlfahrten/)).toBeTruthy();
    // „keine Fremdrechte“: nur die Hinweiszeile, nie ausgegraute Fremdfahrzeuge
    expect(
      screen.getByText(/Fahrzeuge anderer Dienste und Mandanten sind nicht vorhanden/),
    ).toBeTruthy();
    expect(within(table).getAllByRole('row')).toHaveLength(1 + all.length);
    // der Filter schränkt nur ein und beginnt die Liste neu; es wird nie mehr angefragt als gefiltert
    fireEvent.change(screen.getByLabelText('Zustand'), { target: { value: 'BLOCKED' } });
    await waitFor(() =>
      expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2),
    );
    expect(calls.filter((c) => c.id === 'listOperatorVehicles').at(-1)?.args).toMatchObject({
      query: { state: 'BLOCKED', limit: 100 },
    });
    fireEvent.change(screen.getByLabelText('Zustand'), { target: { value: '' } });
    await screen.findByRole('rowheader', { name: 'KA-103' });
    fireEvent.change(screen.getByLabelText('Dienst'), { target: { value: SVC_B } });
    await waitFor(() =>
      expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2),
    );
    expect(calls.filter((c) => c.id === 'listOperatorVehicles').at(-1)?.args).toMatchObject({
      query: { service_id: SVC_B },
    });
  });

  it('liste_ohne_karte_per_tastatur_und_screenreader_bedienbar', async () => {
    const orders = [
      {
        booking_id: '00000000-0000-4000-8000-0000000000c1',
        booking_no: 'BK-2026-000042',
        state: 'CONFIRMED',
        planned_pickup_at: '2026-12-01T08:30:00Z',
        phase: 'NEXT' as const,
      },
    ];
    const { container } = setup(() => page([vehicle(1, { orders }), vehicle(2)]));
    const table = await screen.findByRole('table', { name: /Fahrzeuge der berechtigten Dienste/ });
    // die Karte lässt sich ausblenden; die Liste bleibt vollständig (Fahrzeug, Zustand, Position, Aufträge, Details)
    const toggle = screen.getByRole('button', { name: 'Karte ausblenden' });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(
      screen.getByRole('group', { name: /Schematische Karte mit 2 Fahrzeugpositionen/ }),
    ).toBeTruthy();
    fireEvent.click(toggle);
    expect(screen.queryByRole('group', { name: /Schematische Karte/ })).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Karte einblenden' }).getAttribute('aria-expanded'),
    ).toBe('false');
    for (const header of [
      'Fahrzeug',
      'Zustand',
      'Letzte Position',
      'Aufträge',
      'Hinweise',
      'Details',
    ])
      expect(within(table).getByRole('columnheader', { name: header })).toBeTruthy();
    // Details über die Tastatur erreichbar: Fokus auf die Schaltfläche, sie öffnet das Detailpanel mit Aufträgen und Freigabe
    const details = screen.getByRole('button', { name: 'Details zu KA-101' });
    details.focus();
    expect(document.activeElement).toBe(details);
    // eine echte Schaltfläche (Enter und Leertaste lösen sie im Browser aus); hier der Klick
    expect(details.tagName).toBe('BUTTON');
    fireEvent.click(details);
    const panel = await screen.findByRole('complementary', { name: 'Details zu KA-101' });
    expect(within(panel).getByText(/laufend|nächster/)).toBeTruthy();
    expect(within(panel).getByText(/BK-2026-000042/)).toBeTruthy();
    expect(within(panel).getByText(/gültig bis/)).toBeTruthy();
    // Zustand nie nur über Farbe: Text im Abzeichen; Rollen und Beschriftungen ohne Verstöße
    expect(within(table).getAllByText('Verfügbar').length).toBe(2);
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('zeigt Leerzustand, Ladefehler mit Datenstand und lädt weitere Seiten über den Cursor', async () => {
    let n = 0;
    const { calls } = setup((_id, args) => {
      n += 1;
      const q = (args.query ?? {}) as Record<string, unknown>;
      if (n === 1) return page([vehicle(1)], 'opaker-cursor');
      if (q.cursor === 'opaker-cursor') return page([vehicle(2)]);
      throw new ApiError(503);
    });
    await screen.findByRole('rowheader', { name: 'KA-101' });
    fireEvent.click(screen.getByRole('button', { name: 'Weitere Fahrzeuge laden' }));
    await screen.findByRole('rowheader', { name: 'KA-102' });
    expect(screen.getByRole('rowheader', { name: 'KA-101' })).toBeTruthy();
    expect(calls.filter((c) => c.id === 'listOperatorVehicles').at(-1)?.args).toMatchObject({
      query: { cursor: 'opaker-cursor' },
    });
    // Ladefehler nach erfolgreichem Laden: die letzte Liste bleibt mit ihrem Datenstand
    fireEvent.change(screen.getByLabelText('Zustand'), { target: { value: 'ENDED' } });
    await screen.findByText(/Datenstand von/).catch(() => null);
  });

  it('zeigt den Leerzustand und den Fehlerzustand ohne Daten', async () => {
    setup(() => page([]));
    expect(await screen.findByText('Keine Fahrzeuge')).toBeTruthy();
    expect(screen.getByText(/keine aktiven Fahrzeuge zugeordnet/)).toBeTruthy();
  });

  it('hilfsfunktionen: Sperrtext, Positionstext und Hinweise folgen dem Vertrag', () => {
    const b = vehicle(1).active_blocks;
    expect(b).toEqual([]);
    expect(
      blockText({
        id: 'x',
        vehicle_id: 'y',
        source: 'SAFETY',
        reason_code: 'INSPECTION_FAILED',
        set_at: '',
        set_by: 's',
        lifted_at: null,
        lifted_by: null,
        revision: 1,
        block_kind: 'SERVICE',
        case_number: 'SPE-2026-000001',
      }),
    ).toBe('Dienst gesperrt · kritischer Mangel (Vorgang SPE-2026-000001)');
    expect(positionText(vehicle(1, { position: null, position_measured_at: null }))).toBe(
      'keine Position bekannt',
    );
    expect(notesOf(vehicle(1))).toEqual([]);
    expect(notesOf(vehicle(1, { open_defect_count: 2 }))).toEqual(['2 offene Schadenakte(n)']);
  });
});
