import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import {
  AuthProvider,
  DraftProvider,
  SessionProvider,
  keycloakConfig,
  useAuth,
} from '@taktovia/api-client';
import type { AuthConfig } from '@taktovia/api-client';
import { createFakeApi, createMockIdp } from '@taktovia/api-client/testing';
import { App } from '../App';
import { AuthGate } from './AuthGate';

const A = '00000000-0000-4000-8000-00000000000a';
const USER = '33333333-3333-4333-8333-333333333333';

function Authenticated() {
  const { client } = useAuth();
  return (
    <SessionProvider client={client}>
      <DraftProvider>
        <App />
      </DraftProvider>
    </SessionProvider>
  );
}

async function start(directory: Parameters<typeof createFakeApi>[1]) {
  const idp = createMockIdp();
  const api = createFakeApi(idp, directory);
  const navigate = vi.fn<(url: string) => void>();
  const location = { search: '', pathname: '/konto' };
  const m = new Map<string, string>();
  const config: AuthConfig = {
    oidc: {
      ...keycloakConfig('https://idp.test/realms/t', 'passenger-web', 'https://app.test'),
      tokenEndpoint: idp.tokenEndpoint,
    },
    baseUrl: 'https://api.test',
    storage: {
      getItem: (k) => m.get(k) ?? null,
      setItem: (k, v) => void m.set(k, v),
      removeItem: (k) => void m.delete(k),
    },
    navigate,
    location: () => location,
    replaceUrl: vi.fn(),
    fetch: ((url: string | URL | Request, init?: RequestInit) =>
      String(url).startsWith('https://idp.test')
        ? idp.fetch(url, init)
        : api.fetch(url, init)) as typeof fetch,
  };
  const tree = (
    <MemoryRouter initialEntries={['/konto']}>
      <AuthProvider config={config}>
        <AuthGate>
          <Authenticated />
        </AuthGate>
      </AuthProvider>
    </MemoryRouter>
  );
  const first = render(tree);
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
  await waitFor(() => expect(navigate).toHaveBeenCalled());
  const url = navigate.mock.calls[0]?.[0] ?? '';
  expect(new URL(url).searchParams.get('acr_values')).toBeNull(); // Fahrgäste ohne Administratorrolle: kein zweiter Faktor vorab
  first.unmount();
  location.search = idp.authorize(url, USER);
  render(tree);
  return { api, navigate };
}

describe('Anmeldung in der Passenger-PWA (WP-M1-44, UI-05 Basis)', () => {
  it('Login, eigene Sitzungen im Konto, „Alle Geräte abmelden“ führt zurück zur Anmeldeseite', async () => {
    const { api, navigate } = await start({
      [USER]: [
        {
          id: A,
          name: 'Verkehrsbetrieb Nord',
          roles: ['PASSENGER'],
          scopes: ['me:read', 'me:write', 'tenant:switch', 'journeys:book'],
        },
      ],
    });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Konto' }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole('table', { name: 'Meine Sitzungen im aktiven Mandanten' }),
    ).toHaveTextContent('dieses Gerät');
    navigate.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Alle Geräte abmelden' }));
    await waitFor(() =>
      expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'signedOut'),
    );
    expect(api.log.some((l) => l.path === '/v1/me/sessions/revoke-all' && l.status === 204)).toBe(
      true,
    );
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('ohne Mitgliedschaft: verständlicher Zustand, keine Datenanfrage über /v1/me hinaus', async () => {
    const { api } = await start({ [USER]: [] });
    await waitFor(
      () => expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'noMembership'),
      { timeout: 5000 },
    );
    expect(api.log.filter((l) => l.path !== '/v1/me')).toEqual([]);
    expect(screen.queryByRole('navigation')).toBeNull();
  });
});
