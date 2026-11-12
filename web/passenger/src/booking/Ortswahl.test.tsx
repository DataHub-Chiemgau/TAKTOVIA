import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, PlaceHit, Profile } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { Ortswahl, toRidePlace } from './Ortswahl';
import type { PlaceChoice } from './Ortswahl';

const STOP = '11111111-1111-4111-8111-111111111111';
const hit = (
  type: PlaceHit['type'],
  label: string,
  lat: number,
  lon: number,
  extra: Partial<PlaceHit> = {},
): PlaceHit => ({
  type,
  label,
  point: { latitude: lat, longitude: lon },
  stop_id: null,
  source: 'test',
  data_as_of: '2026-10-25T08:00:00Z',
  service_area: 'INSIDE',
  nearest_stop: null,
  ...extra,
});

interface Server {
  calls: { id: string; query: Record<string, unknown> }[];
  /** Antworten je Suchtext (Teilstring, klein); sonst leere Treffer. */
  answers: Record<string, PlaceHit[]>;
  notices: ('GEOCODER_UNAVAILABLE' | 'NO_MATCH')[];
  fail?: number;
  favorites: Profile['favorites'];
}

function fakeServer(init: Partial<Server> = {}) {
  const state: Server = { calls: [], answers: {}, notices: [], favorites: [], ...init };
  const client = {
    call(id: string, args: { query?: Record<string, unknown> }) {
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['PASSENGER'] }));
      if (id === 'getMyProfile')
        return Promise.resolve({
          id: 'x',
          status: 'ACTIVE',
          locale: 'de-DE',
          created_at: '2026-10-25T08:00:00Z',
          revision: 1,
          contact: { email: null, phone: null },
          favorites: state.favorites,
          preferred_stops: [],
          notification_channels: [],
        });
      if (id === 'searchPlaces') {
        const query = args.query ?? {};
        state.calls.push({ id, query });
        if (state.fail) return Promise.reject(new ApiError(state.fail));
        const q = String(query.q ?? '').toLowerCase();
        const items =
          query.map_latitude !== undefined
            ? [
                hit(
                  'MAP_POINT',
                  'Punkt auf der Karte',
                  Number(query.map_latitude),
                  Number(query.map_longitude),
                ),
              ]
            : (Object.entries(state.answers).find(([k]) => q.includes(k))?.[1] ?? []);
        return Promise.resolve({
          items,
          notices: state.notices,
          as_of: '2026-10-25T08:00:00Z',
        });
      }
      return Promise.reject(new ApiError(404));
    },
  } as unknown as ApiClient;
  return { state, client };
}

function Harness({
  onValue,
  serviceId,
}: {
  onValue?: (c: PlaceChoice | null) => void;
  serviceId?: string;
}) {
  const [value, setValue] = useState<PlaceChoice | null>(null);
  return (
    <Ortswahl
      label="Start"
      serviceId={serviceId}
      value={value}
      onChange={(c) => {
        setValue(c);
        onValue?.(c);
      }}
    />
  );
}

function show(client: ApiClient, onValue?: (c: PlaceChoice | null) => void) {
  return render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <Harness
          {...(onValue ? { onValue } : {})}
          serviceId="22222222-2222-4222-8222-222222222222"
        />
      </SessionProvider>
    </MemoryRouter>,
  );
}

const input = () => screen.getByRole('combobox', { name: 'Start' });
const type = (text: string) => fireEvent.change(input(), { target: { value: text } });

describe('Ortssuche mit fünf Eingabearten (WP-M2-38)', () => {
  afterEach(cleanup);

  it('fuenf_eingabearten_liefern_gueltige_orte_fuer_ride_offer', async () => {
    const server = fakeServer({
      favorites: [
        { label: 'Zuhause', lat: 51.04, lon: 13.75, stop_id: null, stop_bookable: false },
      ],
      answers: {
        haupt: [hit('ADDRESS', 'Hauptstraße 1, Dresden', 51.05, 13.74)],
        zwinger: [hit('POI', 'Zwinger', 51.053, 13.734)],
        altm: [hit('STOP', 'Altmarkt', 51.05, 13.74, { stop_id: STOP })],
      },
    });
    const chosen: PlaceChoice[] = [];
    show(server.client, (c) => c && chosen.push(c));

    for (const [text, name] of [
      ['Haupt', /Hauptstraße 1/],
      ['Zwinger', /Zwinger/],
      ['Altm', /Altmarkt/],
    ] as const) {
      type(text);
      fireEvent.click(await screen.findByRole('option', { name }));
      fireEvent.click(screen.getByRole('button', { name: 'Start Ändern' }));
    }
    // Favorit: leere Eingabe zeigt die Favoriten, ohne Suchanfrage
    fireEvent.focus(input());
    fireEvent.click(await screen.findByRole('option', { name: /Zuhause/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Start Ändern' }));
    // Kartenpunkt
    fireEvent.click(screen.getByRole('button', { name: 'Punkt auf Karte wählen' }));
    fireEvent.change(screen.getByLabelText('Start: Breite'), { target: { value: '51,0505' } });
    fireEvent.change(screen.getByLabelText('Start: Länge'), { target: { value: '13.7405' } });
    fireEvent.click(screen.getByRole('button', { name: 'Kartenpunkt prüfen' }));
    fireEvent.click(await screen.findByRole('option', { name: /Punkt auf der Karte/ }));

    expect(chosen.map((c) => c.type)).toEqual(['ADDRESS', 'POI', 'STOP', 'FAVORITE', 'MAP_POINT']);
    for (const c of chosen) {
      const place = toRidePlace(c);
      expect(Math.abs(place.point.latitude)).toBeLessThanOrEqual(90);
      expect(Math.abs(place.point.longitude)).toBeLessThanOrEqual(180);
      expect(place.point.latitude === 0 && place.point.longitude === 0).toBe(false);
    }
    expect(toRidePlace(chosen[2] as PlaceChoice).stop_id).toBe(STOP);
    expect(toRidePlace(chosen[3] as PlaceChoice).stop_id).toBeUndefined();
    // nur drei Textsuchen und eine Kartenpunktprüfung gingen an die API, die Favoritenauswahl nicht
    expect(server.state.calls).toHaveLength(4);
    expect(server.state.calls.every((c) => c.query.service_id !== undefined)).toBe(true);
  });

  it('vorschlaege_kennzeichnen_den_typ_und_bestaetigter_treffpunkt_wird_gezeigt', async () => {
    const server = fakeServer({
      answers: { alt: [hit('STOP', 'Altmarkt', 51.05, 13.74, { stop_id: STOP })] },
    });
    show(server.client);
    type('alt');
    const option = await screen.findByRole('option', { name: /Altmarkt/ });
    expect(option).toHaveTextContent('Haltestelle');
    fireEvent.click(option);
    const bestaetigt = screen.getByTestId('treffpunkt');
    expect(bestaetigt).toHaveTextContent('Start: Altmarkt');
    expect(bestaetigt).toHaveTextContent('Treffpunkt bestätigt');
  });

  it('zu_kurze_eingabe_loest_keine_anfrage_aus', async () => {
    const server = fakeServer();
    show(server.client);
    type('a');
    expect(
      await screen.findByText('Bitte geben Sie mindestens 2 Zeichen ein.'),
    ).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 400));
    expect(server.state.calls).toHaveLength(0);
  });

  it('nicht_aufloesbarer_text_zeigt_meldung_und_keine_koordinate', async () => {
    const server = fakeServer({ notices: ['NO_MATCH'] });
    show(server.client);
    type('Quuxfeld');
    expect(await screen.findByText(/Kein Ort gefunden/)).toBeInTheDocument();
    expect(screen.queryByRole('option')).toBeNull();
    expect(screen.queryByTestId('treffpunkt')).toBeNull();
  });

  it('geocoder_ausfall_laesst_haltestelle_favorit_und_kartenpunkt_nutzbar', async () => {
    const server = fakeServer({
      notices: ['GEOCODER_UNAVAILABLE'],
      answers: { alt: [hit('STOP', 'Altmarkt', 51.05, 13.74, { stop_id: STOP })] },
    });
    show(server.client);
    type('alt');
    expect(
      await screen.findByText(/Adress- und Ortssuche ist gerade nicht verfügbar/),
    ).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('option', { name: /Altmarkt/ }));
    expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Altmarkt');
  });

  it('treffer_ausserhalb_des_bediengebiets_wird_gekennzeichnet', async () => {
    const server = fakeServer({
      answers: {
        ber: [hit('ADDRESS', 'Berliner Straße 2, Berlin', 52.5, 13.4, { service_area: 'OUTSIDE' })],
      },
    });
    const chosen: PlaceChoice[] = [];
    show(server.client, (c) => c && chosen.push(c));
    type('Berlin');
    const option = await screen.findByRole('option', { name: /Berliner/ });
    expect(option).toHaveTextContent('außerhalb Bediengebiet');
    fireEvent.click(option);
    expect(screen.getByRole('alert')).toHaveTextContent('kann nicht gebucht werden');
    expect(chosen[0]?.outsideServiceArea).toBe(true);
  });

  it('markup_in_der_eingabe_und_in_treffern_wird_als_text_dargestellt', async () => {
    const evil = '<img src=x onerror=alert(1)>';
    const server = fakeServer({ answers: { img: [hit('POI', evil, 51.05, 13.74)] } });
    const { container } = show(server.client);
    type(evil);
    await screen.findByRole('option');
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByRole('option')).toHaveTextContent(evil);
    expect((input() as HTMLInputElement).value).toBe(evil);
  });

  it('zu_viele_anfragen_zeigt_meldung_und_ort_bleibt_waehlbar', async () => {
    const server = fakeServer({
      fail: 429,
      favorites: [{ label: 'Zuhause', lat: 51, lon: 13, stop_id: null, stop_bookable: false }],
    });
    show(server.client);
    type('Haupt');
    expect(await screen.findByText(/Zu viele Suchanfragen/)).toBeInTheDocument();
  });

  it('ungueltiger_kartenpunkt_wird_abgewiesen', async () => {
    const server = fakeServer();
    show(server.client);
    fireEvent.click(screen.getByRole('button', { name: 'Punkt auf Karte wählen' }));
    for (const [lat, lon] of [
      ['91', '10'],
      ['10', '181'],
      ['0', '0'],
      ['abc', '1'],
    ]) {
      fireEvent.change(screen.getByLabelText('Start: Breite'), { target: { value: lat } });
      fireEvent.change(screen.getByLabelText('Start: Länge'), { target: { value: lon } });
      fireEvent.click(screen.getByRole('button', { name: 'Kartenpunkt prüfen' }));
      expect(await screen.findByText(/müssen gültige Zahlen sein/)).toBeInTheDocument();
    }
    expect(server.state.calls).toHaveLength(0);
  });

  it('tastaturbedienung_und_barrierefreiheit', async () => {
    const server = fakeServer({
      answers: { alt: [hit('STOP', 'Altmarkt', 51.05, 13.74, { stop_id: STOP })] },
    });
    const { container } = show(server.client);
    type('alt');
    await screen.findByRole('option', { name: /Altmarkt/ });
    expect(input()).toHaveAttribute('aria-expanded', 'true');
    expect(formatViolations(await runAxe(container))).toBe('');
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    expect(input().getAttribute('aria-activedescendant')).toBeTruthy();
    fireEvent.keyDown(input(), { key: 'Enter' });
    await waitFor(() => expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Altmarkt'));
  });
});
