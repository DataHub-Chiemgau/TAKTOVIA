import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, PlaceHit } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { StandortFreigabe } from './LocationConsent';
import { Start } from './Start';

const SERVICE = '22222222-2222-4222-8222-222222222222';

type Success = (p: { coords: { latitude: number; longitude: number; accuracy: number } }) => void;
type Failure = (e: { code: number }) => void;

/** Steuerbarer Ersatz für `navigator.geolocation`: die Rückrufe löst der Test selbst aus. */
const geo = {
  getCurrentPosition: vi.fn<(ok: Success, fail: Failure, options?: unknown) => void>(),
  watchPosition: vi.fn(),
};
let permissionState: 'granted' | 'prompt' | 'denied' = 'prompt';

function installGeolocation(present = true) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: present ? geo : undefined,
  });
  // `'geolocation' in navigator` muss bei „nicht verfügbar“ falsch sein
  if (!present) Reflect.deleteProperty(navigator, 'geolocation');
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    value: { query: vi.fn(() => Promise.resolve({ state: permissionState })) },
  });
}

const hit = (label: string, lat: number, lon: number, source = 'test'): PlaceHit => ({
  type: 'MAP_POINT',
  label,
  point: { latitude: lat, longitude: lon },
  stop_id: null,
  source,
  data_as_of: '2026-10-25T08:00:00Z',
  service_area: 'INSIDE',
  nearest_stop: null,
});

interface Fake {
  client: ApiClient;
  calls: { id: string; query?: Record<string, unknown> | undefined; body?: unknown }[];
}

function fakeClient(): Fake {
  const calls: Fake['calls'] = [];
  const client = {
    call(id: string, args: { query?: Record<string, unknown>; body?: unknown }) {
      calls.push({ id, query: args.query, body: args.body });
      if (id === 'getMe')
        return Promise.resolve({ ...sampleMe({ roles: ['PASSENGER'] }), service_scope: [SERVICE] });
      if (id === 'getMyProfile') return Promise.reject(new ApiError(404));
      if (id === 'searchPlaces') {
        const q = args.query ?? {};
        if (q.map_latitude !== undefined)
          return Promise.resolve({
            items: [
              hit('Teststraße 5, Dresden', Number(q.map_latitude), Number(q.map_longitude), 'osm'),
            ],
            notices: [],
            as_of: '2026-10-25T08:00:00Z',
          });
        const text = String(q.q ?? '');
        return Promise.resolve({
          items: [{ ...hit(`${text}-Ort`, 51.05, 13.74), type: 'ADDRESS' }],
          notices: [],
          as_of: '2026-10-25T08:00:00Z',
        });
      }
      return Promise.reject(new ApiError(404));
    },
  } as unknown as ApiClient;
  return { client, calls };
}

function showStart(fake = fakeClient()) {
  render(
    <MemoryRouter>
      <SessionProvider client={fake.client}>
        <Start />
      </SessionProvider>
    </MemoryRouter>,
  );
  return fake;
}

const button = () => screen.getByRole('button', { name: 'Meinen Standort verwenden' });
const accept = () => fireEvent.click(screen.getByRole('button', { name: 'Standort abfragen' }));
const last = () => {
  const call = geo.getCurrentPosition.mock.calls.at(-1);
  if (!call) throw new Error('keine Standortabfrage ausgelöst');
  return { ok: call[0], fail: call[1] };
};

/** Start und Ziel von Hand wählen (Texteingabe, Vorschlag anklicken). */
async function chooseManually() {
  for (const label of ['Start', 'Ziel']) {
    if (screen.queryByRole('combobox', { name: label })) {
      fireEvent.change(screen.getByRole('combobox', { name: label }), {
        target: { value: `${label}ort` },
      });
      fireEvent.click(await screen.findByRole('option', { name: new RegExp(`${label}ort`) }));
    }
  }
  return screen.findByTestId('orte-bereit');
}

describe('Standortfreigabe nur nach Zustimmung (WP-M2-39)', () => {
  beforeEach(() => {
    geo.getCurrentPosition.mockReset();
    geo.watchPosition.mockReset();
    permissionState = 'prompt';
    installGeolocation(true);
  });
  afterEach(cleanup);

  it('keine_standortabfrage_ohne_nutzeraktion', async () => {
    showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    // weder beim Seitenaufruf noch beim Tippen, Wählen oder Öffnen des Dialogs
    fireEvent.change(screen.getByRole('combobox', { name: 'Start' }), { target: { value: 'Alt' } });
    fireEvent.click(await screen.findByRole('option'));
    fireEvent.click(button());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(geo.watchPosition).not.toHaveBeenCalled();
    // erst die Bestätigung im Dialog fragt ab
    accept();
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(geo.watchPosition).not.toHaveBeenCalled();
  });

  it('erteilte_freigabe_bietet_startvorschlag_zur_bestaetigung', async () => {
    const fake = showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.click(button());
    accept();
    act(() => last().ok({ coords: { latitude: 51.0505, longitude: 13.7405, accuracy: 30 } }));
    // Vorschlag, aber noch kein Start gewählt
    const vorschlag = await screen.findByTestId('standort-vorschlag');
    expect(vorschlag).toHaveTextContent('als Start verfügbar');
    expect(screen.queryByTestId('treffpunkt')).toBeNull();
    expect(fake.calls.some((c) => c.id === 'searchPlaces')).toBe(false);
    fireEvent.click(within(vorschlag).getByRole('button', { name: 'Als Start übernehmen' }));
    await waitFor(() => expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Teststraße 5'));
    // Verwerfen ohne Wirkung
    expect(screen.queryByTestId('standort-vorschlag')).toBeNull();
  });

  it('verworfener_vorschlag_setzt_keinen_start', async () => {
    showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.click(button());
    accept();
    act(() => last().ok({ coords: { latitude: 51.05, longitude: 13.74, accuracy: 30 } }));
    fireEvent.click(await screen.findByRole('button', { name: 'Verwerfen' }));
    expect(screen.queryByTestId('treffpunkt')).toBeNull();
    expect(screen.getByRole('combobox', { name: 'Start' })).toBeInTheDocument();
  });

  it('abgelehnte_freigabe_erlaubt_manuelle_buchung', async () => {
    showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.click(button());
    accept();
    await act(async () => last().fail({ code: 1 }));
    expect(await screen.findByTestId('standort-hinweis')).toHaveTextContent('abgelehnt');
    // Start und Ziel von Hand: der Weg bis zu bestätigten Orten (Eingang der Fahrtanfrage) ist vollständig
    const ready = await chooseManually();
    expect(JSON.parse(ready.getAttribute('data-origin') ?? '{}')).toMatchObject({
      point: { latitude: 51.05, longitude: 13.74 },
    });
    expect(JSON.parse(ready.getAttribute('data-destination') ?? '{}')).toHaveProperty('point');
  });

  it('blockiert_nicht_verfuegbar_timeout_ungenau_erlauben_manuelle_buchung', async () => {
    const cases: { name: string; text: RegExp; trigger: () => Promise<void> }[] = [
      {
        name: 'dauerhaft blockiert',
        text: /blockiert/,
        trigger: async () => {
          permissionState = 'denied';
          fireEvent.click(button());
          accept();
          await act(async () => last().fail({ code: 1 }));
        },
      },
      {
        name: 'Position nicht verfügbar',
        text: /gerade nicht verfügbar/,
        trigger: async () => {
          fireEvent.click(button());
          accept();
          await act(async () => last().fail({ code: 2 }));
        },
      },
      {
        name: 'Zeitüberschreitung',
        text: /zu lange gedauert/,
        trigger: async () => {
          fireEvent.click(button());
          accept();
          await act(async () => last().fail({ code: 3 }));
        },
      },
      {
        name: 'ungenaue Position',
        text: /zu ungenau/,
        trigger: async () => {
          fireEvent.click(button());
          accept();
          await act(async () =>
            last().ok({ coords: { latitude: 51.05, longitude: 13.74, accuracy: 5000 } }),
          );
        },
      },
      {
        name: 'keine Standortunterstützung im Browser',
        text: /unterstützt keine Standortabfrage/,
        trigger: async () => {
          cleanup();
          installGeolocation(false);
          showStart();
          await screen.findByRole('combobox', { name: 'Start' });
        },
      },
    ];
    for (const c of cases) {
      cleanup();
      geo.getCurrentPosition.mockReset();
      permissionState = 'prompt';
      installGeolocation(true);
      showStart();
      await screen.findByRole('combobox', { name: 'Start' });
      await c.trigger();
      expect(await screen.findByText(c.text), c.name).toBeInTheDocument();
      expect(screen.queryByTestId('standort-vorschlag'), c.name).toBeNull();
      const ready = await chooseManually();
      expect(ready, c.name).toBeInTheDocument();
    }
  });

  it('manuelle_wahl_wird_von_spaeter_freigabe_nicht_ueberschrieben', async () => {
    showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.click(button());
    accept();
    // Fahrgast wählt, während die Abfrage läuft, selbst einen Start
    fireEvent.change(screen.getByRole('combobox', { name: 'Start' }), {
      target: { value: 'Manuell' },
    });
    fireEvent.click(await screen.findByRole('option', { name: /Manuell-Ort/ }));
    expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Manuell-Ort');
    act(() => last().ok({ coords: { latitude: 52, longitude: 14, accuracy: 20 } }));
    expect(screen.queryByTestId('standort-vorschlag')).toBeNull();
    expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Manuell-Ort');
  });

  it('freigabe_nach_abbruch_oder_seitenwechsel_ergibt_keinen_startvorschlag', async () => {
    const first = showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    // Abbruch im laufenden Zustand: Dialog schließen ist vor der Abfrage; danach Seitenwechsel
    fireEvent.click(button());
    accept();
    const pending = last();
    cleanup(); // Seitenwechsel: Komponente verschwindet
    showStart(first);
    await screen.findByRole('combobox', { name: 'Start' });
    act(() => pending.ok({ coords: { latitude: 51.05, longitude: 13.74, accuracy: 20 } }));
    expect(screen.queryByTestId('standort-vorschlag')).toBeNull();
    // Abbruch durch erneutes Öffnen und Schließen des Dialogs
    fireEvent.click(button());
    fireEvent.click(screen.getByRole('button', { name: 'Abbrechen' }));
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('standort_wird_nicht_persistiert', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const cookie = vi.spyOn(document, 'cookie', 'set');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const fake = showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    setItem.mockClear();
    fireEvent.click(button());
    accept();
    act(() => last().ok({ coords: { latitude: 51.0123, longitude: 13.7654, accuracy: 25 } }));
    fireEvent.click(await screen.findByRole('button', { name: 'Als Start übernehmen' }));
    await screen.findByTestId('treffpunkt');
    expect(setItem).not.toHaveBeenCalled();
    expect(cookie).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(JSON.stringify(window.localStorage) + JSON.stringify(window.sessionStorage)).not.toMatch(
      /51\.0123|13\.7654/,
    );
    // schreibende Befehle gab es nicht: nur Lesen (searchPlaces) mit der Koordinate
    expect(
      fake.calls.filter((c) => JSON.stringify(c).includes('51.0123')).map((c) => c.id),
    ).toEqual(['searchPlaces']);
    expect(fake.calls.every((c) => c.body === undefined)).toBe(true);
    setItem.mockRestore();
    cookie.mockRestore();
    fetchSpy.mockRestore();
  });

  it('standort_geht_nur_an_geocoding_adapter', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const beacon = vi.fn();
    Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: beacon });
    const fake = showStart();
    await screen.findByRole('combobox', { name: 'Start' });
    fireEvent.click(button());
    accept();
    act(() => last().ok({ coords: { latitude: 51.0123, longitude: 13.7654, accuracy: 25 } }));
    fireEvent.click(await screen.findByRole('button', { name: 'Als Start übernehmen' }));
    await screen.findByTestId('treffpunkt');
    // spätere Textsuche trägt die Koordinate als Suchkoordinate derselben Operation
    fireEvent.change(screen.getByRole('combobox', { name: 'Ziel' }), { target: { value: 'Ziel' } });
    await screen.findByRole('option');
    const withCoordinates = fake.calls.filter((c) => JSON.stringify(c).includes('51.0123'));
    expect(withCoordinates.length).toBeGreaterThanOrEqual(2);
    expect(new Set(withCoordinates.map((c) => c.id))).toEqual(new Set(['searchPlaces']));
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(beacon).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it('dialog_per_tastatur_und_screenreader_bedienbar', async () => {
    const onConfirm = vi.fn();
    const { container } = render(<StandortFreigabe startChosen={false} onConfirm={onConfirm} />);
    const trigger = button();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog');
    // benannt und beschrieben, modal
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog', { name: 'Standort verwenden?' })).toBe(dialog);
    expect(dialog).toHaveAccessibleDescription(/nicht gespeichert/);
    // Fokus im Dialog, Tab bleibt darin
    const ja = screen.getByRole('button', { name: 'Standort abfragen' });
    const nein = screen.getByRole('button', { name: 'Abbrechen' });
    expect(ja).toHaveFocus();
    nein.focus();
    fireEvent.keyDown(nein, { key: 'Tab' });
    expect(ja).toHaveFocus();
    fireEvent.keyDown(ja, { key: 'Tab', shiftKey: true });
    expect(nein).toHaveFocus();
    expect(formatViolations(await runAxe(container))).toBe('');
    // Escape schließt und gibt den Fokus zurück, ohne abzufragen
    fireEvent.keyDown(nein, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(trigger).toHaveFocus();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
  });
});
