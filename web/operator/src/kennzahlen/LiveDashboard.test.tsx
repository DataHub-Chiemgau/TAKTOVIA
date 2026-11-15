import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  AnalyticsMetrics,
  ApiClient,
  LiveDashboard as LiveData,
  LiveTile,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { LiveRegionProvider } from '@taktovia/design-system';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { LiveDashboard, formatAge } from './LiveDashboard';

type Model = { live: () => LiveData | ApiError };

const tile = (
  id: LiveTile['id'],
  value: number | null,
  over: Partial<LiveTile> = {},
): LiveTile => ({
  id,
  value,
  as_of: value === null ? null : '2026-06-01T08:00:00Z',
  age_seconds: value === null ? null : 5,
  state: value === null ? 'NO_DATA' : 'FRESH',
  details: {},
  ...over,
});
const live = (outages: number | null, tiles?: LiveTile[]): LiveData => ({
  evaluated_at: '2026-06-01T08:00:05Z',
  as_of: '2026-06-01T08:00:00Z',
  refresh_seconds: 30,
  stale_after_seconds: 120,
  tolerance_min: 5,
  alert_min: 15,
  scope: 'TENANT',
  tiles: tiles ?? [
    tile('open_requests', 3),
    tile('available_capacity', 4, { details: { seats: 16, wheelchair_spaces: 2 } }),
    tile('critical_delays', 1),
    tile('vehicle_outages', outages, { details: { long_outages: 0 } }),
  ],
});

/** Modell des Servers (kein Ersatz für LiveDashboardTest/LiveDashboardApiTest; ein Browser-E2E mit Screenreader ist hier NICHT ausgeführt). */
function setup(scopes: string[], model: Model) {
  const calls: { id: OperationId; query: Record<string, unknown> | undefined }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: { query?: Record<string, unknown> }) => {
      calls.push({ id, query: args?.query });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      const r = model.live();
      if (r instanceof ApiError) return Promise.reject(r);
      return Promise.resolve({
        definitions: [],
        values: null,
        rejections: null,
        funnel: null,
        not_performed: null,
        live: r,
      } as AnalyticsMetrics);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter>
      <LiveRegionProvider>
        <SessionProvider client={client}>
          <LiveDashboard />
        </SessionProvider>
      </LiveRegionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
afterEach(() => vi.useRealTimers());

describe('Live-Dashboard (WP-M4-60, KPI-014)', () => {
  it('zeigt_vier_getrennte_kacheln_mit_datenstand_und_abfrageart_live', async () => {
    const { calls, container } = setup(['analytics:read'], { live: () => live(2) });
    for (const name of [
      'Offene Anfragen',
      'Verfügbare Kapazität',
      'Kritische Verspätungen',
      'Fahrzeugausfälle',
    ])
      expect(await screen.findByRole('heading', { name })).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'getAnalyticsMetrics')?.query).toEqual({ breakdown: 'live' });
    const cap = screen.getByRole('region', { name: 'Verfügbare Kapazität' });
    expect(within(cap).getByText('4 Fahrzeuge, 16 Sitze, 2 Rollstuhlplätze')).toBeInTheDocument();
    const out = screen.getByRole('region', { name: 'Fahrzeugausfälle' });
    expect(within(out).getByText(/^2 \(davon 0 lang\)$/)).toBeInTheDocument();
    expect(
      within(out).getByText(/Datenstand 2026-06-01T08:00:00Z \(vor 5 s\)/),
    ).toBeInTheDocument();
    expect(within(out).getByText('Aktuell')).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('aktualisiert_im_abfrageintervall_ohne_fokus_oder_elementwechsel_und_meldet_die_aenderung_hoeflich', async () => {
    let outages = 0;
    setup(['analytics:read'], { live: () => live(outages) });
    const heading = await screen.findByRole('heading', { name: 'Fahrzeugausfälle' });
    const section = heading.closest('section');
    const region = document.querySelector('[role="status"][aria-live="polite"]');
    expect(region).not.toBeNull();
    // Fokus auf einem Element außerhalb der Kacheln bleibt erhalten
    const probe = document.createElement('button');
    probe.textContent = 'Fokusprobe';
    document.body.appendChild(probe);
    probe.focus();
    outages = 1; // Fahrzeugsperre
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(await screen.findByText(/^1 \(davon 0 lang\)$/)).toBeInTheDocument();
    expect(document.activeElement).toBe(probe);
    // dieselben DOM-Elemente: kein Neuaufbau, keine Verschiebung
    expect(screen.getByRole('heading', { name: 'Fahrzeugausfälle' }).closest('section')).toBe(
      section,
    );
    await act(async () => {
      await Promise.resolve();
    });
    expect(region?.textContent).toContain('Live-Werte geändert: Fahrzeugausfälle 1 (davon 0 lang)');
    probe.remove();
  });

  it('veralteter_datenstand_und_fehlender_datenstand_erscheinen_nie_als_aktueller_wert', async () => {
    const stale = live(1, [
      tile('open_requests', null),
      tile('available_capacity', null, { details: { seats: null, wheelchair_spaces: null } }),
      tile('critical_delays', 0, {
        state: 'STALE',
        age_seconds: 900,
        as_of: '2026-06-01T07:45:00Z',
      }),
      tile('vehicle_outages', 1, {
        state: 'STALE',
        age_seconds: 900,
        as_of: '2026-06-01T07:45:00Z',
        details: { long_outages: 1 },
      }),
    ]);
    setup(['analytics:read'], { live: () => stale });
    const out = await screen.findByRole('region', { name: 'Fahrzeugausfälle' });
    expect(within(out).getByText('Veraltet')).toBeInTheDocument();
    expect(within(out).getByText(/vor 15 min/)).toBeInTheDocument();
    expect(within(out).getByText(/zuletzt bekannter Wert/)).toBeInTheDocument();
    expect(out).toHaveAttribute('data-state', 'STALE');
    const open = screen.getByRole('region', { name: 'Offene Anfragen' });
    expect(within(open).getByText('kein Wert')).toBeInTheDocument(); // nie 0
    expect(within(open).getByText('Kein Datenstand')).toBeInTheDocument();
    expect(within(open).getByText(/noch keine Ereignisse verarbeitet/)).toBeInTheDocument();
  });

  it('ausfall_der_aktualisierung_zeigt_warnung_und_kennzeichnet_die_letzten_werte_als_veraltet', async () => {
    let fail = false;
    setup(['analytics:read'], { live: () => (fail ? new ApiError(503) : live(2)) });
    await screen.findByRole('region', { name: 'Fahrzeugausfälle' });
    fail = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Aktualisierung fehlgeschlagen (Status 503)',
    );
    const out = screen.getByRole('region', { name: 'Fahrzeugausfälle' });
    expect(within(out).getByText('Veraltet')).toBeInTheDocument();
    expect(within(out).getByText(/^2 \(davon 0 lang\)$/)).toBeInTheDocument();
    fail = false;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(
      within(screen.getByRole('region', { name: 'Fahrzeugausfälle' })).getByText('Aktuell'),
    ).toBeInTheDocument();
  });

  it('ohne_berichtsrecht_wird_nichts_abgerufen', async () => {
    const { calls } = setup([], { live: () => live(0) });
    expect(await screen.findByText(/nicht freigeschaltet/)).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(calls.some((c) => c.id === 'getAnalyticsMetrics')).toBe(false);
  });

  it('kacheln_sind_per_ueberschrift_navigierbar_und_haben_keine_fokussprung_ziele', async () => {
    const { container } = setup(['analytics:read'], { live: () => live(2) });
    await screen.findByRole('region', { name: 'Fahrzeugausfälle' });
    expect(container.querySelectorAll('section h2')).toHaveLength(4);
    expect(container.querySelectorAll('[tabindex],[autofocus]')).toHaveLength(0);
    fireEvent.keyDown(document.body, { key: 'Tab' });
    expect(
      document.activeElement === document.body || container.contains(document.activeElement),
    ).toBe(true);
  });

  it('formatiert_das_datenalter', () => {
    expect(formatAge(5)).toBe('vor 5 s');
    expect(formatAge(900)).toBe('vor 15 min');
    expect(formatAge(7200)).toBe('vor 2 h');
  });
});
