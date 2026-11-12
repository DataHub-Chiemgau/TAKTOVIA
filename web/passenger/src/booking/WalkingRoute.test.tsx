import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { RideBooking, RideWalkRoute } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { WalkingRoute } from './WalkingRoute';
import { basisBuchung } from './reiseWelt';

const ZONE = 'Europe/Berlin';

function weg(over: Partial<RideWalkRoute> = {}): RideWalkRoute {
  return {
    status: 'ROUTE',
    snapshot_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccc01',
    distance_m: 310,
    duration_s: 250,
    profile: 'walk',
    map_version: 'referenz-2026-11-01',
    fallback_reason: null,
    geometry: [
      { latitude: 51.0486, longitude: 13.74 },
      { latitude: 51.049, longitude: 13.74 },
      { latitude: 51.049, longitude: 13.741 },
      { latitude: 51.05, longitude: 13.741 },
    ],
    steps: [
      {
        seq: 1,
        maneuver: 'START',
        distance_m: 45,
        duration_s: 36,
        instruction: 'Gehen Sie Richtung Norden los und folgen Sie dem Weg 45 m.',
      },
      {
        seq: 2,
        maneuver: 'TURN_RIGHT',
        distance_m: 70,
        duration_s: 56,
        instruction: 'Biegen Sie nach rechts ab und gehen Sie 70 m.',
      },
      {
        seq: 3,
        maneuver: 'TURN_LEFT',
        distance_m: 110,
        duration_s: 158,
        instruction: 'Biegen Sie nach links ab und gehen Sie 110 m.',
      },
      {
        seq: 4,
        maneuver: 'ARRIVE',
        distance_m: 0,
        duration_s: 0,
        instruction: 'Sie haben den Treffpunkt „Altmarkt“ erreicht. Gesamtstrecke 310 m.',
      },
    ],
    pickup_stop_name: 'Altmarkt',
    computed_at: '2026-11-21T06:00:00Z',
    ...over,
  };
}

const zeige = (b: RideBooking) => render(<WalkingRoute booking={b} zone={ZONE} />);

describe('Fußweg zum Treffpunkt mit Textanleitung (WP-M2-44)', () => {
  afterEach(cleanup);

  it('karte_und_textanleitung_sind_aequivalent', () => {
    const w = weg();
    zeige(basisBuchung({ walk: w }));
    const karte = screen.getByTestId('fussweg-karte');
    const liste = screen.getByTestId('fussweg-anleitung');
    // beide tragen denselben Snapshot und dieselben Angaben: Strecke, Gehzeit, Zahl der Abschnitte, Wegpunkte
    expect(karte.getAttribute('data-snapshot')).toBe(w.snapshot_id);
    expect(liste.getAttribute('data-snapshot')).toBe(w.snapshot_id);
    expect(karte.getAttribute('data-points')).toBe(String(w.geometry.length));
    expect(liste.getAttribute('data-steps')).toBe(String(w.steps.length));
    expect(within(liste).getAllByRole('listitem')).toHaveLength(w.steps.length);
    expect(karte.getAttribute('aria-label')).toContain('310 m');
    expect(karte.getAttribute('aria-label')).toContain('5 Minuten');
    expect(karte.getAttribute('aria-label')).toContain(`${w.steps.length - 1} Wegabschnitte`);
    expect(screen.getByTestId('fussweg-summe')).toHaveTextContent('Zu Fuß etwa 5 Minuten (310 m)');
    // der Linienzug hat genau die Wegpunkte
    expect(karte.querySelector('polyline')?.getAttribute('points')?.split(' ')).toHaveLength(4);
  });

  it('textanleitung_ohne_karte_vollstaendig_und_screenreader_reihenfolge', async () => {
    const w = weg();
    const { container } = zeige(basisBuchung({ walk: w }));
    // ohne Karte: die geordnete Liste trägt alle Schritte in Wegreihenfolge, die Karte ist nur ein benanntes Bild und nicht Träger der Information
    const liste = screen.getByRole('list', { name: 'Wegbeschreibung' });
    const texte = within(liste)
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(texte).toEqual(w.steps.map((s) => s.instruction));
    expect(liste.tagName).toBe('OL');
    expect(texte[0]).toContain('Richtung Norden');
    expect(texte[texte.length - 1]).toContain('Treffpunkt „Altmarkt“');
    // Reihenfolge im Dokument: Zusammenfassung, Karte, Anleitung, Stand
    const order = Array.from(
      container.querySelectorAll(
        '[data-testid="fussweg-summe"], [data-testid="fussweg-karte"], [data-testid="fussweg-anleitung"]',
      ),
    ).map((e) => e.getAttribute('data-testid'));
    expect(order).toEqual(['fussweg-summe', 'fussweg-karte', 'fussweg-anleitung']);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('rueckfall_ist_als_rueckfall_gekennzeichnet', () => {
    const w = weg({
      status: 'FALLBACK',
      fallback_reason: 'ROUTING_UNAVAILABLE',
      distance_m: 160,
      duration_s: 128,
      geometry: [],
      steps: [
        {
          seq: 1,
          maneuver: 'ARRIVE',
          distance_m: 160,
          duration_s: 128,
          instruction:
            'Der Fußweg konnte nicht berechnet werden. Der Treffpunkt „Altmarkt“ liegt etwa 160 m Luftlinie entfernt (ungefähr 3 Minuten zu Fuß).',
        },
      ],
    });
    zeige(basisBuchung({ walk: w }));
    expect(screen.getByTestId('fussweg-rueckfall-marke')).toHaveTextContent('Rückfall');
    expect(screen.getByTestId('fussweg-rueckfall')).toHaveTextContent('keine Wegführung');
    expect(screen.getByTestId('fussweg-summe')).toHaveTextContent('Luftlinie');
    // keine erfundene Karte, Name des Treffpunkts und Luftlinie stehen in der Anleitung
    expect(screen.queryByTestId('fussweg-karte')).toBeNull();
    expect(screen.getByTestId('fussweg-anleitung')).toHaveTextContent('Altmarkt');
    expect(screen.getByTestId('fussweg-anleitung')).toHaveTextContent('Luftlinie');
  });

  it('start_gleich_haltepunkt_zeigt_keinen_weg_und_ohne_walk_einen_hinweis', () => {
    const { unmount } = zeige(
      basisBuchung({
        walk: weg({ status: 'NO_WALK', distance_m: 0, duration_s: 0, geometry: [], steps: [] }),
      }),
    );
    expect(screen.getByTestId('fussweg-nicht-noetig')).toHaveTextContent('bereits am Treffpunkt');
    expect(screen.queryByTestId('fussweg-karte')).toBeNull();
    unmount();
    const { walk: _w, ...ohne } = basisBuchung();
    void _w;
    zeige(ohne as RideBooking);
    expect(screen.getByTestId('fussweg-keiner')).toHaveTextContent('Treffpunkt: Altmarkt');
  });

  it('planrevision_aktualisiert_weg_und_text_gemeinsam', () => {
    const alt = weg();
    const neu = weg({
      snapshot_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccc02',
      distance_m: 120,
      duration_s: 96,
      pickup_stop_name: 'Hauptbahnhof',
      geometry: [
        { latitude: 51.04, longitude: 13.73 },
        { latitude: 51.041, longitude: 13.73 },
      ],
      steps: [
        {
          seq: 1,
          maneuver: 'START',
          distance_m: 111,
          duration_s: 96,
          instruction: 'Gehen Sie Richtung Norden los und folgen Sie dem Weg 111 m.',
        },
        {
          seq: 2,
          maneuver: 'ARRIVE',
          distance_m: 0,
          duration_s: 0,
          instruction: 'Sie haben den Treffpunkt „Hauptbahnhof“ erreicht. Gesamtstrecke 120 m.',
        },
      ],
    });
    const { rerender } = zeige(basisBuchung({ walk: alt }));
    const gleich = () => {
      const k = screen.getByTestId('fussweg-karte').getAttribute('data-snapshot');
      const t = screen.getByTestId('fussweg-anleitung').getAttribute('data-snapshot');
      expect(k).toBe(t);
      return k;
    };
    expect(gleich()).toBe(alt.snapshot_id);
    rerender(<WalkingRoute booking={basisBuchung({ walk: neu })} zone={ZONE} />);
    // Weg und Text wechseln gemeinsam, nie gemischt
    expect(gleich()).toBe(neu.snapshot_id);
    expect(screen.getByTestId('fussweg-anleitung')).toHaveTextContent('Hauptbahnhof');
    expect(screen.getByTestId('fussweg-anleitung')).not.toHaveTextContent('Altmarkt');
    expect(screen.getByTestId('fussweg-karte').getAttribute('data-distance')).toBe('120');
    expect(screen.getByTestId('fussweg-karte').getAttribute('data-points')).toBe('2');
  });
});
