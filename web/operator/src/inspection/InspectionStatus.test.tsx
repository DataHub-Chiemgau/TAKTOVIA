import { act, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  InspectionStatus,
  OperationId,
  OperatorVehicle,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import {
  INSPECTION_STATUS,
  InspectionStatusBadge,
  mergeInspectionStatus,
} from './InspectionStatus';

const ALL: InspectionStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'PASSED',
  'RESTRICTED',
  'BLOCKED',
  'UNCLEAR',
];

const vehicle = (
  n: number,
  status: InspectionStatus = 'PENDING',
  revision = 1,
): OperatorVehicle => ({
  vehicle_id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  vehicle_label: `KA-${100 + n}`,
  operational_state: 'AVAILABLE',
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
  inspection_status: status,
  inspection_status_revision: revision,
  service_ids: [],
  unavailable_dimensions: [],
  open_alert_count: 0,
  orders: [],
});

const enc = new TextEncoder();
const event = (cursor: string, type: string): string =>
  `id: ${cursor}\nevent: live\ndata: ${JSON.stringify({ event_id: `e-${cursor}`, cursor, type, topic: type.split('.')[0], aggregate_id: 'a', aggregate_revision: 2, service_id: null, shift_id: null, status: {}, occurred_at: '2026-12-01T06:00:00Z' })}\n\n`;

function controlled() {
  let ctl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      ctl = c;
    },
  });
  return {
    response: new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
    push: (s: string) => ctl.enqueue(enc.encode(s)),
    fail: () => ctl.error(new Error('abgebrochen')),
  };
}

describe('UI-07 Prüfstatus (WP-M3-23, LH-6.21-CHK-016)', () => {
  it('status_nie_nur_ueber_farbe_text_und_symbol', async () => {
    const { container } = render(
      <ul>
        {ALL.map((s) => (
          <li key={s}>
            <InspectionStatusBadge status={s} />
          </li>
        ))}
      </ul>,
    );
    // sechs Werte: je ein eigener Text und ein eigenes Symbol (Symbol dekorativ, der Text trägt die Bedeutung)
    expect(ALL.map((s) => INSPECTION_STATUS[s].label)).toEqual([
      'Ausstehend',
      'In Bearbeitung',
      'Bestanden',
      'Eingeschränkt',
      'Gesperrt',
      'Technisch ungeklärt',
    ]);
    expect(new Set(ALL.map((s) => INSPECTION_STATUS[s].icon)).size).toBe(6);
    for (const s of ALL) {
      const badge = container.querySelector(`[data-inspection-status="${s}"]`) as HTMLElement;
      expect(badge).toHaveTextContent(INSPECTION_STATUS[s].label);
      const icon = badge.querySelector('[data-icon]') as HTMLElement;
      expect(icon).toHaveTextContent(INSPECTION_STATUS[s].icon);
      expect(icon).toHaveAttribute('aria-hidden', 'true');
    }
    // möglicherweise veraltet: Text, nicht nur Farbe
    render(<InspectionStatusBadge status="PASSED" stale />);
    expect(screen.getByText(/möglicherweise veraltet/)).toBeInTheDocument();
    expect(await runAxe(container)).toEqual([]);
  });

  it('aelteres_ereignis_ueberschreibt_hoehere_revision_nicht', () => {
    const shown = [vehicle(1, 'BLOCKED', 7)];
    // eine überholte Antwort (Revision 5, bestanden) darf den angezeigten Stand (Revision 7, gesperrt) nicht überschreiben
    const merged = mergeInspectionStatus(shown, [vehicle(1, 'PASSED', 5)]);
    expect(merged[0]?.inspection_status).toBe('BLOCKED');
    expect(merged[0]?.inspection_status_revision).toBe(7);
    // gleiche oder höhere Revision: Serverstand gilt
    expect(mergeInspectionStatus(shown, [vehicle(1, 'PASSED', 8)])[0]?.inspection_status).toBe(
      'PASSED',
    );
    expect(mergeInspectionStatus(shown, [vehicle(1, 'BLOCKED', 7)])[0]?.inspection_status).toBe(
      'BLOCKED',
    );
    // ohne bisherigen Stand und für neue Fahrzeuge gilt die Antwort, entfallene Fahrzeuge verschwinden
    expect(mergeInspectionStatus(undefined, [vehicle(2, 'UNCLEAR', 1)])).toHaveLength(1);
    expect(
      mergeInspectionStatus(shown, [vehicle(2, 'PASSED', 1)]).map((v) => v.vehicle_label),
    ).toEqual(['KA-102']);
  });

  it('statuswechsel_ohne_neuladen_und_veraltet_kennzeichnung', async () => {
    const stream = controlled();
    let served: OperatorVehicle[] = [vehicle(1, 'PASSED', 3)];
    const client = {
      call: vi.fn((id: OperationId) => {
        if (id === 'getMe')
          return Promise.resolve(
            sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
          );
        if (id === 'listOperatorVehicles')
          return Promise.resolve({
            items: served,
            next_cursor: null,
            as_of: '2026-12-01T08:05:00Z',
          });
        return Promise.reject(new ApiError(404));
      }),
      openStream: vi.fn(() => Promise.resolve(stream.response)),
    } as unknown as ApiClient;
    render(
      <MemoryRouter initialEntries={['/leitstelle/fahrzeuge']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    const table = await screen.findByRole('table', { name: /Fahrzeuge der berechtigten Dienste/ });
    const row = () => within(table).getByRole('row', { name: /KA-101/ });
    await waitFor(() => expect(within(row()).getByText('Bestanden')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
    // Statuswechsel auf dem Server; das Kanalereignis stößt das Nachladen an: ohne Neuladen der Seite erscheint der neue Status
    served = [vehicle(1, 'BLOCKED', 4)];
    await act(async () => {
      stream.push(`event: ready\ndata: {"cursor":"5.1","as_of":"x"}\n\n`);
      stream.push(event('5.2', 'fleet.vehicle_blocked'));
    });
    await waitFor(() => expect(within(row()).getByText('Gesperrt')).toBeInTheDocument());
    expect(within(row()).queryByText(/möglicherweise veraltet/)).toBeNull();
    // Kanalabbruch: der zuletzt gezeigte Status bleibt mit dem Datenstand stehen und wird als möglicherweise veraltet gekennzeichnet
    await act(async () => {
      stream.fail();
    });
    await waitFor(() =>
      expect(within(row()).getByText(/möglicherweise veraltet/)).toBeInTheDocument(),
    );
    expect(within(row()).getByText('Gesperrt')).toBeInTheDocument();
    expect(screen.getAllByText(/Datenstand/).length).toBeGreaterThan(0);
  });
});
