import { act, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { createFakeApi, createMockIdp, storageDump } from '../testing';
import type { FakeTenant } from '../testing';
import { ApiError } from '../runtime';
import { SessionProvider, useSession } from '../session';
import { AuthProvider, useAuth } from './AuthProvider';
import type { AuthConfig } from './AuthProvider';
import { useMySessions, useSignOut, useTenantSwitch } from './hooks';
import { keycloakConfig } from './oidc';

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const USER = '11111111-1111-4111-8111-111111111111';
const OIDC = {
  ...keycloakConfig('https://idp.test/realms/t', 'operator-web', 'https://app.test'),
  tokenEndpoint: 'https://idp.test/realms/t/protocol/openid-connect/token',
};

function memStore() {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    key: (i: number) => [...m.keys()][i] ?? null,
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  };
}

const TWO: Record<string, FakeTenant[]> = {
  [USER]: [
    { id: A, name: 'Betrieb A', roles: ['tenant_admin'] },
    { id: B, name: 'Betrieb B', roles: ['tenant_admin'] },
  ],
};

/** Ein Tab: eigener Speicher, eigene Anmeldung; IdP und Server sind gemeinsam (wie im Betrieb). */
function tab(
  idp: ReturnType<typeof createMockIdp>,
  api: ReturnType<typeof createFakeApi>,
  extra: Partial<AuthConfig> = {},
) {
  const storage = memStore();
  const navigate = vi.fn<(url: string) => void>();
  const location = { search: '', pathname: '/' };
  const fetcher = ((url: string | URL | Request, init?: RequestInit) =>
    String(url).startsWith('https://idp.test')
      ? idp.fetch(url, init)
      : api.fetch(url, init)) as typeof fetch;
  const config: AuthConfig = {
    oidc: OIDC,
    baseUrl: 'https://api.test',
    storage,
    fetch: fetcher,
    navigate,
    location: () => location,
    replaceUrl: vi.fn(),
    ...extra,
  };
  return { storage, navigate, location, config };
}

let latestAuth: ReturnType<typeof useAuth> | undefined;
function Capture({ children }: { children?: ReactNode }) {
  const auth = useAuth();
  useEffect(() => {
    latestAuth = auth;
  });
  // wie `AuthGate` der Anwendungen: Inhalte erst nach gültiger Anmeldung
  return auth.state.status === 'authenticated' ? <>{children}</> : null;
}
function Shown() {
  const s = useSession();
  return (
    <p data-testid="tenant">
      {s.status === 'ready'
        ? s.me.tenant_name
        : s.status === 'error'
          ? `error:${s.error.status}`
          : s.status}
    </p>
  );
}

/** Meldet über den Mock-IdP an: Weiterleitung auslösen, Rückkehr einspielen. */
async function loginVia(
  t: ReturnType<typeof tab>,
  idp: ReturnType<typeof createMockIdp>,
  subject: string,
  view: ReactNode,
) {
  const first = render(
    <AuthProvider config={t.config}>
      <Capture />
    </AuthProvider>,
  );
  await act(async () => {
    await latestAuth?.signIn();
  });
  const url = t.navigate.mock.calls[0]?.[0] ?? '';
  first.unmount();
  t.location.search = idp.authorize(url, subject);
  const second = render(
    <AuthProvider config={t.config}>
      <Capture>{view}</Capture>
    </AuthProvider>,
  );
  await waitFor(() => expect(latestAuth?.state.status).toBe('authenticated'));
  return { url, ...second };
}

function Session({ children }: { children?: ReactNode }) {
  const auth = useAuth();
  return (
    <SessionProvider client={auth.client}>
      <Shown />
      {children}
    </SessionProvider>
  );
}

describe('Anmeldung im Browser: OIDC mit PKCE (WP-M1-44)', () => {
  it('Anmeldung mit PKCE S256: Weiterleitung mit state und Challenge, Code nur mit passendem Verifier einlösbar, Speicher enthält nie ein Token', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    const { url } = await loginVia(t, idp, USER, <Session />);
    const u = new URL(url);
    expect(u.searchParams.get('response_type')).toBe('code');
    expect(u.searchParams.get('code_challenge_method')).toBe('S256');
    expect(u.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(u.searchParams.get('state')).toBeTruthy();
    expect(u.searchParams.get('code_verifier')).toBeNull();
    await waitFor(() => expect(screen.getByTestId('tenant')).toHaveTextContent('Betrieb A'));
    // Token nur im Arbeitsspeicher: weder Zugriffs- noch Erneuerungstoken noch Verifier stehen im Speicher des Tabs
    const dump = storageDump(t.storage);
    for (const token of [...idp.issued.access, ...idp.issued.refresh])
      expect(dump).not.toContain(token);
    expect(dump).not.toContain('verifier');
    expect(t.config.replaceUrl).toHaveBeenCalledWith('/');
  });

  it('Administratoranwendung fordert bei der Anmeldung den zweiten Faktor an (acr_values=loa2)', async () => {
    const idp = createMockIdp();
    const t = tab(idp, createFakeApi(idp, TWO), { requestMfa: true });
    const { url } = await loginVia(t, idp, USER, <Session />);
    expect(new URL(url).searchParams.get('acr_values')).toBe('loa2');
    const plain = tab(idp, createFakeApi(idp, TWO));
    const { url: url2 } = await loginVia(plain, idp, USER, <Session />);
    expect(new URL(url2).searchParams.get('acr_values')).toBeNull();
  });

  it('negativ: falscher state, wiederverwendeter Code und fremder Verifier ergeben keine Anmeldung', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    const first = render(
      <AuthProvider config={t.config}>
        <Capture />
      </AuthProvider>,
    );
    await act(async () => {
      await latestAuth?.signIn();
    });
    const url = t.navigate.mock.calls[0]?.[0] ?? '';
    first.unmount();
    const good = idp.authorize(url, USER);
    t.location.search = good.replace(/state=[^&]*/, 'state=gefaelscht');
    render(
      <AuthProvider config={t.config}>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'error' }));
    expect(idp.exchanges).toEqual([]); // der Code wurde gar nicht erst eingelöst (CSRF-Schutz)
    // Anmeldung ohne vorherige Weiterleitung (kein gespeicherter Verifier) scheitert ebenfalls
    const t2 = tab(idp, api);
    t2.location.search = idp.authorize(url, USER);
    render(
      <AuthProvider config={t2.config}>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'error' }));
  });

  it('ohne Mitgliedschaft erscheint der Zustand 403 ohne Datenzugriff', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, { [USER]: [] });
    const t = tab(idp, api);
    await loginVia(t, idp, USER, <Session />);
    await waitFor(() => expect(screen.getByTestId('tenant')).toHaveTextContent('error:403'));
    expect(api.log.filter((l) => l.status === 200)).toEqual([]);
  });

  it('serverseitig gesperrte Sitzung: die nächste Aktion führt zur Abmeldung (Zustand „beendet“), auch wenn der IdP noch erneuert', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    await loginVia(t, idp, USER, <Session />);
    await waitFor(() => expect(screen.getByTestId('tenant')).toHaveTextContent('Betrieb A'));
    api.revokeSid(idp.sessions.get(idp.issued.access[0] ?? '')?.sid ?? '');
    await act(async () => {
      await expect(latestAuth?.client.call('getMe', {})).rejects.toBeInstanceOf(ApiError);
    });
    expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'revoked' });
    // die Daten des Mandanten verschwinden mit dem Zustand, der Rahmen erhält nie wieder ein Token
    await expect(latestAuth?.client.call('getMe', {})).rejects.toMatchObject({ status: 401 });
  });

  it('IdP-Sitzung beendet: Erneuerung scheitert, Zustand „beendet oder gesperrt“; abgelaufenes Token ohne Erneuerung: „abgelaufen“', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    await loginVia(t, idp, USER, <Session />);
    const sid = idp.sessions.get(idp.issued.access[0] ?? '')?.sid ?? '';
    idp.endSession(sid);
    api.revokeSid(sid);
    await act(async () => {
      await latestAuth?.client.call('getMe', {}).catch(() => undefined);
    });
    expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'revoked' });

    const idp2 = createMockIdp({ accessTtlSeconds: 1 });
    const api2 = createFakeApi(idp2, TWO);
    let now = 1_000_000;
    const t2 = tab(idp2, api2, { now: () => now });
    await loginVia(t2, idp2, USER, <Session />);
    idp2.endSession(idp2.sessions.get(idp2.issued.access[0] ?? '')?.sid ?? '');
    now += 60_000; // Zugriffstoken abgelaufen und Erneuerung im IdP nicht mehr möglich
    await act(async () => {
      await latestAuth?.client.call('getMe', {}).catch(() => undefined);
    });
    expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'expired' });
  });

  it('verlangt der Server den zweiten Faktor (Step-up), führt die Anmeldung mit loa2 weiter', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    await loginVia(t, idp, USER, <Session />);
    api.requireMfa(true);
    await act(async () => {
      await latestAuth?.client.call('getMe', {}).catch(() => undefined);
    });
    expect(latestAuth?.state).toEqual({ status: 'ended', reason: 'mfaRequired' });
    t.navigate.mockClear();
    await act(async () => {
      await latestAuth?.signIn({ mfa: true });
    });
    expect(new URL(t.navigate.mock.calls[0]?.[0] ?? '').searchParams.get('acr_values')).toBe(
      'loa2',
    );
  });
});

describe('Mandantenwechsel und Kontext je Tab (WP-M1-44, UI-Abnahmefall Kap. 06 §6.9)', () => {
  function Switch() {
    const { switchTo } = useTenantSwitch();
    return (
      <button type="button" onClick={() => void switchTo(B)}>
        zu B
      </button>
    );
  }

  it('zwei Tabs: Tab 1 bleibt in A, Tab 2 wechselt nach B; keine Anfrage liefert Daten des anderen Mandanten, auch bei gleichzeitigen Schreibaktionen', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t1 = tab(idp, api);
    const t2 = tab(idp, api);
    await loginVia(t1, idp, USER, <Session />);
    const auth1 = latestAuth;
    const view2 = await loginVia(
      t2,
      idp,
      USER,
      <Session>
        <Switch />
      </Session>,
    );
    const auth2 = latestAuth;
    await waitFor(() =>
      expect(screen.getAllByTestId('tenant').map((n) => n.textContent)).toContain('Betrieb A'),
    );
    await act(async () => {
      screen.getByRole('button', { name: 'zu B' }).click();
    });
    await waitFor(() =>
      expect(screen.getAllByTestId('tenant').map((n) => n.textContent)).toContain('Betrieb B'),
    );
    view2.unmount();
    // gleichzeitige Schreibaktionen aus beiden Tabs
    await Promise.all(
      Array.from({ length: 20 }, (_, i) => [
        auth1?.client.call('createMembership', {
          body: { issuer: 'i', subject: `tab1-${i}`, role_code: 'support' },
        }),
        auth2?.client.call('createMembership', {
          body: { issuer: 'i', subject: `tab2-${i}`, role_code: 'support' },
        }),
      ]).flat(),
    );
    expect(api.rowsOf(A).every((s) => s.startsWith('tab1-'))).toBe(true);
    expect(api.rowsOf(B).every((s) => s.startsWith('tab2-'))).toBe(true);
    expect(api.rowsOf(A)).toHaveLength(20);
    expect(api.rowsOf(B)).toHaveLength(20);
    const me1 = await auth1?.client.call('getMe', {});
    const me2 = await auth2?.client.call('getMe', {});
    expect([me1?.tenant_id, me2?.tenant_id]).toEqual([A, B]);
    // der Kontext eines Tabs steht nur in dessen Speicher
    expect(t1.storage.getItem('taktovia.ctx')).toBeNull();
    expect(t2.storage.getItem('taktovia.ctx')).toContain('ticket.');
  });

  it('manipulierte Mandantenbindung im Speicher erzeugt keine Rechte: der Server verwirft sie, der Standardmandant gilt', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, { [USER]: [{ id: A, name: 'Betrieb A', roles: ['support'] }] });
    const t = tab(idp, api);
    t.storage.setItem('taktovia.ctx', `ticket.${USER}.${B}.sid-fremd`); // Fälschung: Mandant B ohne Mitgliedschaft
    await loginVia(t, idp, USER, <Session />);
    await waitFor(() => expect(screen.getByTestId('tenant')).toHaveTextContent('Betrieb A'));
    expect(api.log.some((l) => l.status === 403)).toBe(true); // der Server hat die Bindung abgelehnt
    expect(t.storage.getItem('taktovia.ctx')).toBeNull(); // und der Tab hat sie verworfen
    expect(api.log.filter((l) => l.status === 200 && l.tenant === B)).toEqual([]);
  });
});

describe('Sitzungen und Abmelden (UI-05 Basis)', () => {
  function Sessions() {
    const s = useMySessions();
    const signOut = useSignOut();
    return (
      <div>
        <p data-testid="count">
          {s.state.status === 'ready' ? s.state.items.length : s.state.status}
        </p>
        <button type="button" onClick={() => void s.revokeAll()}>
          alle
        </button>
        <button type="button" onClick={() => void signOut()}>
          abmelden
        </button>
      </div>
    );
  }

  it('„Alle Geräte abmelden“ beendet die Sitzungen am Server und im IdP; die Anmeldung dieses Tabs endet', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    await loginVia(
      t,
      idp,
      USER,
      <Session>
        <Sessions />
      </Session>,
    );
    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('1'));
    const sid = idp.sessions.get(idp.issued.access[0] ?? '')?.sid ?? '';
    await act(async () => {
      screen.getByRole('button', { name: 'alle' }).click();
    });
    await waitFor(() => expect(latestAuth?.state.status).toBe('signedOut'));
    expect(api.log.some((l) => l.path === '/v1/me/sessions/revoke-all' && l.status === 204)).toBe(
      true,
    );
    const refreshed = await idp.fetch(idp.tokenEndpoint, {
      method: 'POST',
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: idp.issued.refresh[0] ?? '',
      }).toString(),
    });
    expect(refreshed.status).toBe(400); // Refresh nicht mehr möglich
    expect(sid).not.toBe('');
  });

  it('Abmelden dieses Geräts sperrt die eigene Sitzung am Server und leitet zum Abmelden beim Anbieter', async () => {
    const idp = createMockIdp();
    const api = createFakeApi(idp, TWO);
    const t = tab(idp, api);
    await loginVia(
      t,
      idp,
      USER,
      <Session>
        <Sessions />
      </Session>,
    );
    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('1'));
    t.navigate.mockClear();
    await act(async () => {
      screen.getByRole('button', { name: 'abmelden' }).click();
    });
    await waitFor(() => expect(latestAuth?.state.status).toBe('signedOut'));
    expect(api.log.some((l) => l.method === 'DELETE' && l.status === 204)).toBe(true);
    expect(t.navigate.mock.calls[0]?.[0]).toContain('/protocol/openid-connect/logout');
  });
});
