import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, OperatorVehicle } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { StandortAnzeige, positionText } from './VehicleList';

/**
 * WP-M5-08 (LH-6.19-NFR-005, UI-07): Veraltet-Kennzeichnung des Standorts in der Leitstelle. Die Kennzeichnung steht als Text mit Symbol (nicht nur Farbe, Kap. 06 §6.1), nennt die Zeit der letzten
 * Position (keine Fortschreibung), gilt in der Listenalternative zur Karte und ist für Screenreader wahrnehmbar (Text im Tabelleninhalt, Titel und Beschriftung der Karte).
 */
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
  position_state: 'FRESH',
  position_quality: 'GOOD',
  open_defect_count: 0,
  current_run_id: null,
  revision: 1,
  inspection_status: 'PENDING',
  inspection_status_revision: 1,
  service_ids: [],
  unavailable_dimensions: [],
  open_alert_count: 0,
  orders: [],
  ...over,
});

const STALE = vehicle(2, {
  position_stale: true,
  position_state: 'STALE',
  position_age_seconds: 95,
  position_measured_at: '2026-12-01T07:58:25Z',
});

function setup(items: OperatorVehicle[]) {
  const client = {
    call: vi.fn((id: OperationId) => {
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
        );
      return Promise.resolve({ items, next_cursor: null, as_of: '2026-12-01T08:05:00Z' });
    }),
  } as unknown as ApiClient;
  return render(
    <MemoryRouter initialEntries={['/leitstelle/fahrzeuge']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('UI-07 veralteter Standort (WP-M5-08, NFR-005)', () => {
  it('zeigt_veraltet_kennzeichnung_nicht_nur_ueber_farbe', async () => {
    setup([vehicle(1), STALE]);
    const table = await screen.findByRole('table', { name: /Fahrzeuge der berechtigten Dienste/ });
    const row = within(table).getByRole('row', { name: /KA-102/ });
    // Text und Symbol statt Farbe: ausgeschriebene Kennzeichnung, Zeit der letzten Position und Alter, ohne Fortschreibung
    expect(within(row).getByText('Standort veraltet')).toBeTruthy();
    expect(within(row).getByText(/veraltet · letzter Stand .*vor 95 s/)).toBeTruthy();
    expect(row.querySelector('[data-icon]')?.getAttribute('aria-hidden')).toBe('true');
    expect(row.querySelector('td[data-stale="true"]')).toBeTruthy();
    // der frische Standort trägt keine Kennzeichnung
    const fresh = within(table).getByRole('row', { name: /KA-101/ });
    expect(within(fresh).queryByText('Standort veraltet')).toBeNull();
    expect(within(fresh).getByText(/aktuell · .*vor 4 s/)).toBeTruthy();
    // Listenalternative zur Karte: dieselbe Kennzeichnung steht in der Liste; die Karte benennt den Zustand im Titel und in der sichtbaren Beschriftung (nicht nur gestrichelt)
    const map = screen.getByRole('group', { name: /Schematische Karte mit 2 Fahrzeugpositionen/ });
    expect(within(map).getByText(/KA-102: veraltet · letzter Stand/)).toBeTruthy();
    expect(within(map).getByText(/KA-102 \(veraltet\)/)).toBeTruthy();
    expect(within(map).queryByText(/KA-101 \(veraltet\)/)).toBeNull();
    const violations = await runAxe(document.body);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('veraltete_position_wird_nicht_fortgeschrieben_und_geringe_genauigkeit_ist_benannt', () => {
    // der Text nennt exakt die Zeit der letzten Messung; ohne Position nie eine fiktive Liveposition
    expect(positionText(STALE)).toContain(
      new Date('2026-12-01T07:58:25Z').toLocaleTimeString('de-DE'),
    );
    expect(positionText(vehicle(3, { position: null, position_measured_at: null }))).toBe(
      'keine Position bekannt',
    );
    expect(positionText(vehicle(4, { position_quality: 'LOW_ACCURACY' }))).toContain(
      'geringe Genauigkeit',
    );
    render(
      <StandortAnzeige
        v={vehicle(5, { position: null, position_measured_at: null, position_stale: true })}
      />,
    );
    expect(screen.queryByText('Standort veraltet')).toBeNull();
  });
});
