import { cleanup, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BookingDetail } from './BookingDetail';
import { basisBuchung, reiseWelt, renderReise } from './reiseWelt';

/**
 * WP-M5-08 (LH-6.19-NFR-005, UI-04): Standort des Fahrzeugs in der Reiseverfolgung. Der Server liefert den Standort nur für die eigene aktive Buchung und berechnet das Alter; eine frische Position trägt
 * Koordinate und Zeit, eine veraltete nur die Zeit der letzten Position (keine Live-Position, keine Fortschreibung, keine Grundlage einer laufenden ETA). Die Kennzeichnung ist Text (Screenreader), nicht Farbe.
 */
const ZONE = 'Europe/Berlin';
const vehicle = (
  location?: NonNullable<ReturnType<typeof basisBuchung>['vehicle']>['location'],
) => ({
  label: 'Bus 12',
  vehicle_class: 'KLEINBUS',
  ...(location ? { location } : {}),
});

function zeige(booking = basisBuchung()) {
  const { client } = reiseWelt(booking);
  return renderReise(client, (id) => <BookingDetail bookingId={id} zone={ZONE} pollMs={10} />);
}

describe('UI-04 Standort des Fahrzeugs (WP-M5-08, NFR-005)', () => {
  afterEach(cleanup);

  it('veralteter_standort_nicht_als_live_position_und_nicht_als_eta_grundlage', async () => {
    const basis = basisBuchung();
    zeige({
      ...basis,
      vehicle: vehicle({
        state: 'STALE',
        measured_at: '2026-11-21T07:50:00Z',
        age_seconds: 95,
        position: null,
      }),
      eta: {
        ...(basis.eta as NonNullable<typeof basis.eta>),
        // ein Client-seitig noch „live“ wirkendes Prognosefenster: bei veraltetem Standort wird es nicht als Grundlage gezeigt
        forecast: {
          earliest_at: '2026-11-21T08:01:00Z',
          latest_at: '2026-11-21T08:06:00Z',
          basis: 'LIVE_POSITION',
          data_stand: '2026-11-21T07:50:00Z',
          fallback_reason: null,
          model_version: 1,
          config_version: 'm1-c1',
        },
      },
    });
    const standort = await screen.findByTestId('fahrzeug-standort');
    expect(standort).toHaveAttribute('data-state', 'STALE');
    // Zeit der letzten Position, ausgeschriebene Kennzeichnung (nicht nur Farbe), keine Koordinate, keine Live-Aussage
    expect(standort).toHaveTextContent('Standort veraltet: letzte bekannte Position von');
    expect(standort).toHaveTextContent('08:50 Uhr');
    expect(standort).toHaveTextContent('keine Live-Position');
    expect(standort.textContent).not.toMatch(/\d{2}\.\d{4}\s*N/);
    expect(standort).not.toHaveTextContent('Aktuelle Position');
    // die ETA ruht auf der verbindlichen Planzeit: kein Prognosefenster, Rückfall mit Grund
    const prognose = screen.getByTestId('prognose');
    expect(prognose).toHaveTextContent('Keine aktuelle Prognose, es gilt die geplante Abholzeit');
    expect(prognose).toHaveTextContent('Positionsdaten veraltet');
    expect(prognose).not.toHaveTextContent('09:01 bis 09:06');
    expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:00 Uhr');
    const violations = await runAxe(document.body);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('frischer_standort_zeigt_zeit_und_position_unbekannt_nie_erfunden', async () => {
    zeige({
      ...basisBuchung(),
      vehicle: vehicle({
        state: 'FRESH',
        measured_at: '2026-11-21T07:59:50Z',
        age_seconds: 8,
        position: { latitude: 51.0505, longitude: 13.74 },
      }),
    });
    const standort = await screen.findByTestId('fahrzeug-standort');
    expect(standort).toHaveAttribute('data-state', 'FRESH');
    expect(standort).toHaveTextContent('Aktuelle Position, gemessen um');
    expect(standort).toHaveTextContent('51.0505 N, 13.7400 O');
    expect(standort).not.toHaveTextContent('veraltet');
    cleanup();
    zeige({
      ...basisBuchung(),
      vehicle: vehicle({ state: 'UNKNOWN', measured_at: null, age_seconds: null, position: null }),
    });
    expect(await screen.findByTestId('fahrzeug-standort')).toHaveTextContent(
      'Noch keine Position bekannt',
    );
  });

  it('ohne_standortblock_der_server_liefert_ihn_nur_fuer_aktive_buchungen_wird_nichts_gezeigt', async () => {
    zeige({ ...basisBuchung({ state: 'COMPLETED', bereich: 'vergangen' }), vehicle: vehicle() });
    await screen.findByTestId('fahrzeug');
    expect(screen.queryByTestId('fahrzeug-standort')).toBeNull();
  });
});
