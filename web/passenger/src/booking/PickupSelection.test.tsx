import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, GeoServiceAreaPassenger, GeoStopPassenger } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { PickupSelection } from './PickupSelection';
import type { PickupStop, TileSource } from './PickupSelection';

const SERVICE = '11111111-1111-4111-8111-111111111111';
const ORIGIN = { latitude: 51.05, longitude: 13.74 };

function stop(n: number, name: string, distance: number, over: Partial<GeoStopPassenger> = {}) {
  return {
    stop_id: `00000000-0000-4000-8000-00000000000${n}`,
    revision: 1,
    name,
    point: { latitude: 51.05 + n * 0.001, longitude: 13.74 + n * 0.001 },
    accessibility: { schema_version: '1.0', step_free: 'YES', wheelchair_boarding: 'UNVERIFIED' },
    distance_m: distance,
    ...over,
  } satisfies GeoStopPassenger;
}

const AREA: GeoServiceAreaPassenger = {
  id: '22222222-2222-4222-8222-222222222222',
  service_id: SERVICE,
  revision: 1,
  area: {
    type: 'MultiPolygon',
    coordinates: [
      [
        [
          [13.7, 51.0],
          [13.8, 51.0],
          [13.8, 51.1],
          [13.7, 51.1],
          [13.7, 51.0],
        ],
      ],
    ],
  },
  area_square_km: 77.52,
  bounds: { min_latitude: 51.0, min_longitude: 13.7, max_latitude: 51.1, max_longitude: 13.8 },
  valid_from: '2026-01-01T00:00:00Z',
  valid_to: null,
};

const TILES: TileSource = {
  name: 'Testkacheln',
  attribution: '© Testquelle',
  tileUrl: (z, x, y) => `https://tiles.example.test/${z}/${x}/${y}.png`,
};

/**
 * Ersatz des Servers: `stops` ist die aktuelle Menge der Fahrgastsicht (inaktive Haltepunkte fehlen darin, `getStop` liefert für sie 404).
 * Echtes Verhalten der Fahrgastsicht: StopReadViewTest, PassengerGeoHttpApiTest.
 */
function fakeServer(initial: GeoStopPassenger[], areas: GeoServiceAreaPassenger[] = [AREA]) {
  const state = { stops: [...initial], calls: [] as { id: string; args: unknown }[] };
  const client = {
    call(id: string, args: { path?: { stop_id?: string } }) {
      state.calls.push({ id, args });
      if (id === 'listStops')
        return Promise.resolve({
          items: structuredClone(state.stops),
          next_cursor: null,
          as_of: '2026-11-12T08:00:00Z',
        });
      if (id === 'listServiceAreas')
        return Promise.resolve({ items: areas, next_cursor: null, as_of: '2026-11-12T08:00:00Z' });
      if (id === 'getStop') {
        const hit = state.stops.find((s) => s.stop_id === args.path?.stop_id);
        return hit ? Promise.resolve(structuredClone(hit)) : Promise.reject(new ApiError(404));
      }
      return Promise.reject(new ApiError(404));
    },
  } as unknown as ApiClient;
  return { state, client };
}

function show(
  client: ApiClient,
  onSelect: (s: PickupStop) => void = () => undefined,
  tileSource: TileSource | null = TILES,
) {
  return render(
    <SessionProvider client={client}>
      <PickupSelection
        serviceId={SERVICE}
        origin={ORIGIN}
        tileSource={tileSource}
        onSelect={onSelect}
      />
    </SessionProvider>,
  );
}

const STOPS = [
  stop(1, 'Marktplatz', 120),
  stop(2, 'Rathaus', 260, {
    accessibility: { schema_version: '1.0', step_free: 'NO', wheelchair_boarding: 'NO' },
  }),
  stop(3, 'Bahnhof', 410),
];

describe('Abholpunkt wählen: Karte mit gleichwertiger Textliste (UI-01/UI-02, WP-M2-40, PAX-004)', () => {
  afterEach(cleanup);

  it('abholpunkt_ueber_textliste_ohne_karte_waehlbar', async () => {
    const server = fakeServer(STOPS);
    const picked = vi.fn();
    show(server.client, picked);
    const group = await screen.findByRole('radiogroup', { name: 'Abholpunkt' });
    // ohne jede Karteninteraktion: Auswahl in der Liste, dann Übernehmen
    const options = within(group).getAllByRole('radio');
    expect(options).toHaveLength(3);
    const confirm = screen.getByRole('button', { name: 'Abholpunkt übernehmen' });
    expect(confirm).toBeDisabled();
    fireEvent.click(within(group).getByRole('radio', { name: /Rathaus/ }));
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(picked).toHaveBeenCalledTimes(1));
    expect(picked.mock.calls[0]?.[0]).toMatchObject({
      stop_id: STOPS[1]?.stop_id,
      name: 'Rathaus',
      revision: 1,
    });
    // die Bestätigung hat den Haltepunkt vorher beim Server auf Wirksamkeit geprüft
    expect(server.state.calls.some((c) => c.id === 'getStop')).toBe(true);
    // Karteninteraktion kam dabei nicht vor: keine Karten-Steuerelemente nötig, die Liste enthält Name, Entfernung und Barrierefreiheit
    expect(
      within(group).getByRole('radio', { name: /Marktplatz.*120 m.*stufenfrei/ }),
    ).toBeInTheDocument();
    expect(
      within(group).getByRole('radio', { name: /Rathaus.*nicht stufenfrei/ }),
    ).toBeInTheDocument();
  });

  it('karte_und_liste_zeigen_gleiche_haltepunktmenge', async () => {
    const server = fakeServer(STOPS);
    const picked = vi.fn();
    const { container } = show(server.client, picked);
    const group = await screen.findByRole('radiogroup', { name: 'Abholpunkt' });
    const listIds = within(group)
      .getAllByRole('radio')
      .map((r) => (r as HTMLInputElement).value)
      .sort();
    const markerIds = Array.from(container.querySelectorAll('[data-stop-id]'))
      .map((m) => m.getAttribute('data-stop-id'))
      .sort();
    expect(markerIds).toEqual(listIds);
    expect(listIds).toHaveLength(3);
    // Auswahl über die Karte wählt denselben Eintrag der Liste
    fireEvent.click(container.querySelector(`[data-stop-id="${STOPS[2]?.stop_id}"]`) as Element);
    expect(
      (within(group).getByRole('radio', { name: /Bahnhof/ }) as HTMLInputElement).checked,
    ).toBe(true);
    // Bediengebiet ist auf der Karte als Fläche gezeichnet
    expect(container.querySelector('[data-testid="service-area-shape"]')).not.toBeNull();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('kartenausfall_laesst_liste_funktionsfaehig', async () => {
    const server = fakeServer(STOPS);
    const picked = vi.fn();
    const { container } = show(server.client, picked);
    await screen.findByRole('radiogroup', { name: 'Abholpunkt' });
    const tiles = container.querySelectorAll('image[data-tile]');
    expect(tiles.length).toBeGreaterThan(0);
    fireEvent.error(tiles[0] as Element);
    // Karte verschwindet, ein Hinweis erklärt es, die Liste bleibt vollständig
    expect(await screen.findByRole('status', { name: /Kartenquelle/ })).toHaveTextContent(
      'Die Karte ist gerade nicht erreichbar',
    );
    expect(container.querySelector('[data-testid="stop-map"]')).toBeNull();
    const group = screen.getByRole('radiogroup', { name: 'Abholpunkt' });
    expect(within(group).getAllByRole('radio')).toHaveLength(3);
    fireEvent.click(within(group).getByRole('radio', { name: /Marktplatz/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Abholpunkt übernehmen' }));
    await waitFor(() => expect(picked).toHaveBeenCalledTimes(1));
  });

  it('kein_haltepunkt_in_reichweite_zeigt_leerzustand', async () => {
    const server = fakeServer([]);
    const picked = vi.fn();
    show(server.client, picked);
    expect(await screen.findByText('Kein Haltepunkt in Reichweite')).toBeInTheDocument();
    expect(screen.getByText(/Verschieben Sie den Startpunkt/)).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Abholpunkt übernehmen' })).toBeNull();
    expect(picked).not.toHaveBeenCalled();
  });

  it('liste_per_tastatur_und_screenreader_bedienbar', async () => {
    const server = fakeServer(STOPS);
    const { container } = show(server.client);
    const group = await screen.findByRole('radiogroup', { name: 'Abholpunkt' });
    // Screenreader: Gruppe mit Namen, jede Option mit vollständigem Namen (Haltepunkt, Entfernung, Gehzeit, Barrierefreiheit)
    const first = within(group).getByRole('radio', { name: /Marktplatz/ });
    expect(first).toHaveAccessibleName(/Marktplatz, 120 m, ca\. 2 Min\. zu Fuß, stufenfrei/);
    // Tastatur: alle Optionen sind native, fokussierbare Radios (Pfeiltasten und Leertaste leistet der Browser), kein Eintrag nur per Maus
    for (const r of within(group).getAllByRole('radio')) {
      expect(r.tabIndex).toBeGreaterThanOrEqual(0);
      expect(r).not.toBeDisabled();
    }
    first.focus();
    expect(document.activeElement).toBe(first);
    // Kartenmarker sind für Hilfstechnik ausgeblendet (die Liste ist die gleichwertige Alternative) und nicht in der Tabulatorreihenfolge
    const map = container.querySelector('[data-testid="stop-map"]') as Element;
    expect(map.getAttribute('role')).toBe('img');
    expect(map.getAttribute('aria-label')).toMatch(/3 Haltepunkte/);
    expect(map.querySelectorAll('[tabindex]')).toHaveLength(0);
    // Ergebnisanzahl wird angesagt
    expect(screen.getByRole('status', { name: 'Ergebnis' })).toHaveTextContent(
      '3 Haltepunkte gefunden',
    );
    // Auswahl per Klick (entspricht der Leertaste auf dem fokussierten Radio)
    fireEvent.click(first);
    expect((first as HTMLInputElement).checked).toBe(true);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('deaktivierter_haltepunkt_wird_mit_hinweis_abgewiesen', async () => {
    const server = fakeServer(STOPS);
    const picked = vi.fn();
    show(server.client, picked);
    const group = await screen.findByRole('radiogroup', { name: 'Abholpunkt' });
    fireEvent.click(within(group).getByRole('radio', { name: /Rathaus/ }));
    // Disposition deaktiviert den Haltepunkt, während die Liste offen ist
    server.state.stops = server.state.stops.filter((s) => s.name !== 'Rathaus');
    fireEvent.click(screen.getByRole('button', { name: 'Abholpunkt übernehmen' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Rathaus ist nicht mehr verfügbar');
    expect(picked).not.toHaveBeenCalled();
    // Liste wurde neu geladen, die Auswahl ist zurückgesetzt
    await waitFor(() =>
      expect(
        within(screen.getByRole('radiogroup', { name: 'Abholpunkt' })).getAllByRole('radio'),
      ).toHaveLength(2),
    );
    expect(screen.queryByRole('radio', { name: /Rathaus/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Abholpunkt übernehmen' })).toBeDisabled();
    // eine neue Wahl ist danach möglich
    fireEvent.click(screen.getByRole('radio', { name: /Bahnhof/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Abholpunkt übernehmen' }));
    await waitFor(() => expect(picked).toHaveBeenCalledTimes(1));
  });

  it('textbeschreibung_des_bediengebiets_vorhanden', async () => {
    const server = fakeServer(STOPS);
    show(server.client);
    const region = await screen.findByRole('region', { name: 'Bediengebiet' });
    expect(region).toHaveTextContent(/ca\. 77,5 km²/);
    expect(region).toHaveTextContent(/51,00° bis 51,10° Nord/);
    expect(region).toHaveTextContent(/13,70° bis 13,80° Ost/);
    expect(region).toHaveTextContent(/gültig ab/);
    // ohne wirksames Gebiet: ausdrücklicher Text statt Schweigen
    cleanup();
    show(fakeServer(STOPS, []).client);
    expect(await screen.findByText('Zurzeit ist kein Bediengebiet wirksam.')).toBeInTheDocument();
  });
});
