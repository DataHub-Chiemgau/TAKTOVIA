import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, KmMetrics, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { formatKm, KmClassesView } from './KmClassesView';

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };

/** Modell des Servers für die Maske (kein Ersatz für die echten Backend-Tests; ein Browser-E2E ist hier NICHT ausgeführt). */
function setup(scopes: string[], handlers: Handlers) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ subject: 'finanz-1', roles: ['finance'], scopes }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <KmClassesView />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const base = {
  metric: 'VEHICLE_KM_CLASSES',
  definition_version: 1,
  from: '2026-06-01',
  to: '2026-06-01',
  vehicle_id: null,
  as_of: '2026-06-01T08:20:00Z',
  completeness: 'COMPLETE',
} as const;

/** Musterfahrt: 1,2 km Leerfahrt, 0,9 km Fahrgast, 0,3 km Überführung, 0,2 km Werkstatt, 0,6 km nicht zuordenbar = 3,2 km. */
const MUSTER: KmMetrics = {
  ...base,
  total_km_thousandths: 3200,
  km_thousandths_by_category: { PASSENGER: 900, EMPTY: 1200, TRANSFER: 300, WORKSHOP: 200 },
  unassigned_km_thousandths: 600,
  days: [
    {
      service_day: '2026-06-01',
      vehicle_id: '00000000-0000-4000-8000-0000000000b1',
      run_id: '00000000-0000-4000-8000-0000000000c1',
      as_of: '2026-06-01T08:20:00Z',
      total_km_thousandths: 3200,
      km_thousandths_by_category: { PASSENGER: 900, EMPTY: 1200, TRANSFER: 300, WORKSHOP: 200 },
      unassigned_km_thousandths: 600,
      events_complete: true,
    },
  ],
};

function fill() {
  fireEvent.change(screen.getByLabelText('Erster Betriebstag (JJJJ-MM-TT)'), {
    target: { value: '2026-06-01' },
  });
  fireEvent.change(screen.getByLabelText('Letzter Betriebstag (JJJJ-MM-TT)'), {
    target: { value: '2026-06-01' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
}

describe('Kilometerklassen (WP-M4-42)', () => {
  it('zeigt_nicht_zuordenbar_als_eigene_zeile', async () => {
    const { calls, container } = setup(['analytics:read'], { getAnalyticsMetrics: () => MUSTER });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    const table = await screen.findByRole('table', { name: 'Kilometer je Kategorie' });
    expect(calls.find((c) => c.id === 'getAnalyticsMetrics')?.args).toMatchObject({
      query: { metric: 'VEHICLE_KM_CLASSES', from: '2026-06-01', to: '2026-06-01' },
    });
    const row = (name: string) => within(table).getByRole('row', { name: new RegExp(`^${name}`) });
    expect(within(row('Fahrgastfahrt')).getByText('0,900 km')).toBeInTheDocument();
    expect(within(row('Leerfahrt')).getByText('1,200 km')).toBeInTheDocument();
    expect(within(row('Überführung')).getByText('0,300 km')).toBeInTheDocument();
    expect(within(row('Werkstattfahrt')).getByText('0,200 km')).toBeInTheDocument();
    // eigene Zeile, nicht in eine Kategorie eingerechnet
    const unassigned = within(table).getByRole('rowheader', { name: 'nicht zuordenbar' });
    expect(unassigned.closest('tr')).not.toBe(row('Leerfahrt'));
    expect(within(row('nicht zuordenbar')).getByText('0,600 km')).toBeInTheDocument();
    expect(within(row('Gesamtkilometer')).getByText('3,200 km')).toBeInTheDocument();
    expect(screen.getByText(/Datenstand 2026-06-01T08:20:00Z/)).toBeInTheDocument();
    expect(screen.getByText('Vollständig')).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('leerer_zeitraum_unvollstaendigkeit_ladefehler_und_ohne_recht', async () => {
    const empty: KmMetrics = {
      ...MUSTER,
      as_of: null,
      total_km_thousandths: 0,
      km_thousandths_by_category: { PASSENGER: 0, EMPTY: 0, TRANSFER: 0, WORKSHOP: 0 },
      unassigned_km_thousandths: 0,
      days: [],
    };
    const a = setup(['analytics:read'], { getAnalyticsMetrics: () => empty });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(await screen.findByText('Keine Fahrten im Zeitraum')).toBeInTheDocument();
    a.unmount();

    const b = setup(['analytics:read'], {
      getAnalyticsMetrics: () => ({ ...MUSTER, completeness: 'INCOMPLETE' }),
    });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(await screen.findByText('Unvollständig')).toBeInTheDocument();
    expect(screen.getByText(/Fahrerereignisse sind unvollständig/)).toBeInTheDocument();
    b.unmount();

    const c = setup(['analytics:read'], { getAnalyticsMetrics: () => new ApiError(503) });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
    c.unmount();

    const d = setup([], { getAnalyticsMetrics: () => MUSTER });
    expect(await screen.findByText(/nicht freigeschaltet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    expect(d.calls.some((x) => x.id === 'getAnalyticsMetrics')).toBe(false);
  });

  it('formatiert_ganzzahlige_tausendstel_ohne_rundungsfehler', () => {
    expect(formatKm(0)).toBe('0,000 km');
    expect(formatKm(7)).toBe('0,007 km');
    expect(formatKm(1234567)).toBe('1.234,567 km');
  });
});
