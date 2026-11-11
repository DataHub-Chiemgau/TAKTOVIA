import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { AuthGate } from './AuthGate';

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const USER = '22222222-2222-4222-8222-222222222222';

function memStore() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  };
}

function Authenticated() {
  const { client } = useAuth();
  return (
    <SessionProvider client={client} now={() => Date.parse('2026-11-02T09:00:00+01:00')}>
      <DraftProvider>
        <App />
      </DraftProvider>
    </SessionProvider>
  );
}

/** Operator-Anwendung mit echter Anmeldung (PKCE gegen den lokalen Mock-IdP) und Server-Ersatz; Einstieg über die Rückkehradresse des Anbieters. */
async function start(directory: Parameters<typeof createFakeApi>[1], path = '/admin/kooperation') {
  const idp = createMockIdp();
  const api = createFakeApi(idp, directory);
  const navigate = vi.fn<(url: string) => void>();
  const location = { search: '', pathname: path };
  const storage = memStore();
  const config: AuthConfig = {
    oidc: {
      ...keycloakConfig('https://idp.test/realms/t', 'operator-web', 'https://app.test'),
      tokenEndpoint: idp.tokenEndpoint,
    },
    baseUrl: 'https://api.test',
    requestMfa: true,
    storage,
    navigate,
    location: () => location,
    replaceUrl: vi.fn(),
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
  // 1. nicht angemeldet: Anmeldeseite ohne Daten, Klick führt zum Anbieter (PKCE, zweiter Faktor)
  const first = render(tree);
  expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'signedOut');
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
  await waitFor(() => expect(navigate).toHaveBeenCalled());
  const url = new URL(navigate.mock.calls[0]?.[0] ?? '');
  expect(url.searchParams.get('code_challenge_method')).toBe('S256');
  expect(url.searchParams.get('acr_values')).toBe('loa2');
  first.unmount();
  // 2. Rückkehr vom Anbieter
  location.search = idp.authorize(url.toString(), USER);
  const view = render(tree);
  return { idp, api, view };
}

describe('Anmeldung und Mandantenwechsel im Operator (WP-M1-44)', () => {
  it('Login, Mandantenwechsel mit ungespeicherter Eingabe (Rückfrage, kein Übertrag in den neuen Mandanten, Rückkehr zum Entwurf) und Abmelden', async () => {
    const { api, idp, view } = await start({
      [USER]: [
        { id: A, name: 'Betrieb A', roles: ['tenant_admin'] },
        { id: B, name: 'Betrieb B', roles: ['tenant_admin'] },
      ],
    });
    api.seedRows(A, ['mitglied-in-a']);
    api.seedRows(B, ['mitglied-in-b']);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Kooperation und Rollen' }),
    ).toBeInTheDocument();
    const main = () => screen.getByRole('main');
    expect(await within(main()).findByText('mitglied-in-a')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Arbeitskontext' })).toHaveTextContent('Betrieb A');

    // ungespeicherte Eingabe im Formular von A
    fireEvent.change(
      screen.getByLabelText('Konto (Subject)', {
        selector: '#' + (screen.getAllByLabelText('Konto (Subject)')[0]?.id ?? ''),
      }),
      { target: { value: 'entwurf-in-a' } },
    );
    // Wechsel nach B: Rückfrage wegen ungespeicherter Eingaben
    fireEvent.change(screen.getByLabelText('Mandant wechseln'), { target: { value: B } });
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('ungespeicherte Eingaben');
    expect(dialog).toHaveTextContent('Mitgliedschaft erteilen');
    // „Hier bleiben“: nichts ändert sich
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hier bleiben' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(api.log.some((l) => l.path === '/v1/me/tenant-switch')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Wechseln und Eingaben aufbewahren',
      }),
    );

    // in B: Daten und Kontext des alten Mandanten sind weg, der Entwurf wurde nicht übernommen
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Arbeitskontext' })).toHaveTextContent('Betrieb B'),
    );
    expect(await within(main()).findByText('mitglied-in-b')).toBeInTheDocument();
    expect(within(main()).queryByText('mitglied-in-a')).toBeNull();
    const field = screen.getAllByLabelText('Konto (Subject)')[0] as HTMLInputElement;
    expect(field.value).toBe('');
    expect(
      api.log.filter(
        (l) => l.tenant === B && l.method !== 'GET' && l.path !== '/v1/me/tenant-switch',
      ),
    ).toEqual([]);

    // zurück nach A: Entwurf wiederhergestellt, nur dort
    fireEvent.change(screen.getByLabelText('Mandant wechseln'), { target: { value: A } });
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Arbeitskontext' })).toHaveTextContent('Betrieb A'),
    );
    await waitFor(() =>
      expect((screen.getAllByLabelText('Konto (Subject)')[0] as HTMLInputElement).value).toBe(
        'entwurf-in-a',
      ),
    );
    expect(screen.getByRole('status')).toHaveTextContent('Entwurf');
    expect(formatViolations(await runAxe(view.container))).toBe('');

    // Abmelden: eigene Sitzung am Server gesperrt, Anmeldung beendet, Weiterleitung zum Abmelden beim Anbieter
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Abmelden' }));
    });
    await waitFor(() =>
      expect(screen.getByTestId('auth-state')).toHaveAttribute('data-kind', 'signedOut'),
    );
    expect(api.log.some((l) => l.method === 'DELETE' && l.status === 204)).toBe(true);
    expect(idp.issued.access.length).toBeGreaterThan(0);
  });

  it('serverseitig gesperrte Sitzung: die nächste Aktion führt zur Abmeldung mit dem Zustand „beendet oder gesperrt“ ohne Daten', async () => {
    const { api, idp } = await start({
      [USER]: [{ id: A, name: 'Betrieb A', roles: ['tenant_admin'] }],
    });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Kooperation und Rollen' }),
    ).toBeInTheDocument();
    api.revokeSid(idp.sessions.get(idp.issued.access[0] ?? '')?.sid ?? '');
    // nächste Aktion (Konto öffnen) -> 401 trotz gültigem Token -> Erneuerung bringt nichts -> Abmeldung, keine Daten mehr
    fireEvent.click(screen.getByRole('link', { name: 'Konto' }));
    const state = await screen.findByTestId('auth-state');
    expect(state).toHaveAttribute('data-kind', 'revoked');
    expect(state).toHaveTextContent('Sitzung beendet oder gesperrt');
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByText('Betrieb A')).toBeNull();
  });
});
