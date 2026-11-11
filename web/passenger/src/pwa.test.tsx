import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useEffect } from 'react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  AuthProvider,
  DraftProvider,
  SessionProvider,
  keycloakConfig,
  useAuth,
} from '@taktovia/api-client';
import type { AuthConfig } from '@taktovia/api-client';
import {
  createFakeApi,
  createMockIdp,
  fakeApiClient,
  sampleMe,
} from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from './App';
import { AuthGate } from './auth/AuthGate';
import { ROUTES } from './routen';
import { texte } from './texte';

const A = '00000000-0000-4000-8000-00000000000a';
const USER = '33333333-3333-4333-8333-333333333333';
const DIRECTORY = {
  [USER]: [
    {
      id: A,
      name: 'Verkehrsbetrieb Nord',
      roles: ['PASSENGER'],
      scopes: ['me:read', 'me:write', 'tenant:switch', 'journeys:book'],
    },
  ],
};

let latestAuth: ReturnType<typeof useAuth> | undefined;
function Authenticated() {
  const auth = useAuth();
  useEffect(() => {
    latestAuth = auth;
  });
  return (
    <SessionProvider client={auth.client}>
      <DraftProvider>
        <App />
      </DraftProvider>
    </SessionProvider>
  );
}

function setOnline(value: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(value);
}

/** Vollständiger Aufbau wie in main.tsx (AuthProvider -> AuthGate -> SessionProvider -> App) gegen lokalen IdP- und Server-Ersatz; Web-Speicher des Browsers bleibt echt. */
function mount(path: string, accessTtlSeconds = 300) {
  const idp = createMockIdp({ accessTtlSeconds });
  const api = createFakeApi(idp, DIRECTORY);
  const navigate = vi.fn<(url: string) => void>();
  const location = { search: '', pathname: path };
  const clock = { now: 1_000_000 };
  const config: AuthConfig = {
    oidc: {
      ...keycloakConfig('https://idp.test/realms/t', 'passenger-web', 'https://app.test'),
      tokenEndpoint: idp.tokenEndpoint,
    },
    baseUrl: 'https://api.test',
    navigate,
    location: () => location,
    replaceUrl: vi.fn(),
    now: () => clock.now,
    fetch: ((url: string | URL | Request, init?: RequestInit) =>
      String(url).startsWith('https://idp.test')
        ? idp.fetch(url, init)
        : api.fetch(url, init)) as typeof fetch,
  };
  const tree = (
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider config={config}>
        <AuthGate>
          <Authenticated />
        </AuthGate>
      </AuthProvider>
    </MemoryRouter>
  );
  return { idp, api, navigate, location, clock, tree };
}

async function login(m: ReturnType<typeof mount>) {
  const first = render(m.tree);
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
  await waitFor(() => expect(m.navigate).toHaveBeenCalled());
  const url = m.navigate.mock.calls[0]?.[0] ?? '';
  first.unmount();
  m.location.search = m.idp.authorize(url, USER);
  return render(m.tree);
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  latestAuth = undefined;
});
afterEach(() => vi.restoreAllMocks());

describe('Passenger-PWA Grundgerüst (WP-M2-35)', () => {
  it('Navigation „Meine Reisen | Hilfe“; Routen UI-01 bis UI-05 und Hilfe sind erreichbar', async () => {
    const client = fakeApiClient({ getMe: () => sampleMe({ roles: ['PASSENGER'] }) });
    const seen: Record<string, string> = {};
    for (const [path, heading] of [
      ['/', 'Fahrt suchen'],
      ['/angebote', 'Angebote'],
      ['/checkout/o-1', 'Buchung abschließen'],
      ['/reisen', 'Meine Reisen'],
      ['/reisen/b-1', 'Meine Reisen'],
      ['/konto', 'Konto'],
      ['/hilfe', 'Hilfe'],
    ] as const) {
      const { unmount } = render(
        <MemoryRouter initialEntries={[path]}>
          <SessionProvider client={client}>
            <App />
          </SessionProvider>
        </MemoryRouter>,
      );
      const h1 = await screen.findByRole('heading', { level: 1, name: heading });
      seen[path] = h1.textContent ?? '';
      if (path === '/') {
        const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
        expect(
          within(nav)
            .getAllByRole('link')
            .map((a) => a.textContent),
        ).toEqual(['Meine Reisen', 'Hilfe']);
      }
      unmount();
    }
    expect(Object.keys(seen)).toHaveLength(7);
    // jeder Eintrag des Routenkatalogs ist oben abgedeckt
    expect(ROUTES.map((r) => r.path.replace(/:\w+/, '.+')).sort()).toEqual(
      Object.keys(seen)
        .map((p) => p.replace(/\/(o|b)-1/, '/.+'))
        .sort(),
    );
  });

  it('Seitenrahmen besteht die automatische Barrierefreiheitsprüfung auf allen Routen und nennt Dienst, Status und Datenstand', async () => {
    const client = fakeApiClient({ getMe: () => sampleMe({ roles: ['PASSENGER'] }) });
    for (const path of ['/', '/angebote', '/reisen', '/konto', '/hilfe']) {
      const { container, unmount } = render(
        <MemoryRouter initialEntries={[path]}>
          <SessionProvider client={client} now={() => Date.parse('2026-10-25T09:00:00+01:00')}>
            <App />
          </SessionProvider>
        </MemoryRouter>,
      );
      await screen.findByRole('heading', { level: 1 });
      if (path !== '/hilfe') {
        const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
        expect(ctx).toHaveTextContent('Dienst');
        expect(ctx).toHaveTextContent('Status');
        expect(ctx).toHaveTextContent('Datenstand');
      }
      expect(formatViolations(await runAxe(container)), path).toBe('');
      unmount();
    }
  });

  it.each([
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [412, 'conflict'],
    [428, 'preconditionRequired'],
    [429, 'rateLimited'],
  ])('API-Fehler %i erscheint als Zustand %s, nie als Rohtext', async (status, kind) => {
    const client = fakeApiClient({ getMe: () => new ApiError(status) });
    render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    if (status === 403) {
      // 403 auf /v1/me heißt „keine Mitgliedschaft“ (WP-M1-44)
      expect(await screen.findByTestId('auth-state')).toHaveAttribute('data-kind', 'noMembership');
      return;
    }
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', kind),
    );
    expect(screen.getByTestId('state-view')).not.toHaveTextContent(`HTTP_${status}`);
  });

  it('ohne Verbindung: Offlinehinweis im Rahmen, UI-01 bietet keine Buchung an', async () => {
    setOnline(false);
    const client = fakeApiClient({ getMe: () => sampleMe({ roles: ['PASSENGER'] }) });
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByTestId('offline-banner')).toHaveTextContent('Offline');
    expect(screen.getByTestId('offline-banner')).toHaveAttribute('role', 'status');
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'offline');
    expect(within(screen.getByRole('main')).queryByRole('button')).toBeNull(); // keine Aktion, insbesondere keine Buchung
    expect(formatViolations(await runAxe(container))).toBe('');
    // wieder online: Hinweis verschwindet
    setOnline(true);
    act(() => void window.dispatchEvent(new Event('online')));
    await waitFor(() => expect(screen.queryByTestId('offline-banner')).toBeNull());
  });

  it('Textkatalog: alle Schlüssel haben nicht leere deutsche Werte und folgen dem Schema bereich.name', () => {
    for (const [key, value] of Object.entries(texte)) {
      expect(key).toMatch(/^[a-z0-9]+\.[a-zA-Z0-9.]+$/);
      expect(value.trim().length).toBeGreaterThan(0);
    }
  });
});

describe('Anmeldung und Sitzung der Passenger-PWA gegen den Test-IdP (WP-M2-35 AK 1)', () => {
  it('ohne Anmeldung nur öffentliche Seiten: Hilfe ist sichtbar, geschützte Routen zeigen nur den Anmeldezustand', async () => {
    const hilfe = mount('/hilfe');
    const r1 = render(hilfe.tree);
    expect(await screen.findByRole('heading', { level: 1, name: 'Hilfe' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Hauptnavigation' })).toBeNull();
    expect(hilfe.api.log).toEqual([]); // keine API-Anfrage ohne Anmeldung
    r1.unmount();

    const reisen = mount('/reisen');
    render(reisen.tree);
    expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'signedOut');
    expect(screen.queryByRole('heading', { name: 'Meine Reisen' })).toBeNull();
    expect(reisen.api.log).toEqual([]);
  });

  it('Login, Navigation, Abmeldung; der Browser-Speicher enthält danach kein Zugriffs- oder Erneuerungstoken', async () => {
    const m = mount('/konto');
    await login(m);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Konto' }, { timeout: 5000 }),
    ).toBeInTheDocument();
    const dump = JSON.stringify({ ...localStorage, ...sessionStorage });
    expect(m.idp.issued.access.length).toBeGreaterThan(0);
    for (const token of [...m.idp.issued.access, ...m.idp.issued.refresh])
      expect(dump).not.toContain(token);
    expect(JSON.stringify({ ...localStorage })).toBe('{}'); // persistenter Speicher bleibt leer
    fireEvent.click(await screen.findByRole('button', { name: 'Alle Geräte abmelden' }));
    await waitFor(() =>
      expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'signedOut'),
    );
  });

  it('abgelaufenes Token ohne Erneuerung: Zustand „Sitzung abgelaufen“ ohne Daten', async () => {
    const m = mount('/konto', 1);
    await login(m);
    await screen.findByRole('heading', { level: 1, name: 'Konto' }, { timeout: 5000 });
    m.idp.endSession(m.idp.sessions.get(m.idp.issued.access[0] ?? '')?.sid ?? '');
    m.clock.now += 60_000;
    await act(async () => {
      await latestAuth?.client.call('getMe', {}).catch(() => undefined);
    });
    await waitFor(() =>
      expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'expired'),
    );
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('gesperrte Sitzung: Zustand „beendet oder gesperrt“, keine Daten mehr', async () => {
    const m = mount('/konto');
    await login(m);
    await screen.findByRole('heading', { level: 1, name: 'Konto' }, { timeout: 5000 });
    m.api.revokeSid(m.idp.sessions.get(m.idp.issued.access[0] ?? '')?.sid ?? '');
    await act(async () => {
      await latestAuth?.client.call('getMe', {}).catch(() => undefined);
    });
    await waitFor(() =>
      expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'revoked'),
    );
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('ohne Verbindung und ohne Anmeldung: Offlinezustand statt Weiterleitung zum Anmeldedienst', () => {
    setOnline(false);
    const m = mount('/');
    render(m.tree);
    expect(screen.getByRole('heading', { level: 1, name: 'Sie sind offline' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Anmelden' })).toBeNull();
    expect(m.navigate).not.toHaveBeenCalled();
  });
});

describe('Installierbarkeit (WP-M2-35 AK 3)', () => {
  const file = (rel: string) => join(process.cwd(), rel); // vitest läuft im Paketverzeichnis
  it('Manifest enthält Pflichtfelder und vorhandene Symbole; index.html verweist darauf', () => {
    const manifest = JSON.parse(readFileSync(file('public/manifest.webmanifest'), 'utf8'));
    expect(manifest.display).toBe('standalone');
    expect(manifest.start_url).toBe('/');
    expect(manifest.name).toBeTruthy();
    const sizes = manifest.icons.map((i: { sizes: string }) => i.sizes);
    expect(sizes).toEqual(expect.arrayContaining(['192x192', '512x512']));
    for (const icon of manifest.icons as { src: string }[]) {
      const bytes = readFileSync(file(`public${icon.src}`));
      expect(bytes.subarray(1, 4).toString()).toBe('PNG');
    }
    expect(readFileSync(file('index.html'), 'utf8')).toContain(
      'rel="manifest" href="/manifest.webmanifest"',
    );
    expect(readFileSync(file('public/sw.js'), 'utf8')).toContain("addEventListener('fetch'");
  });
});
