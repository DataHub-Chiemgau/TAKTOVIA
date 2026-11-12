import { describe, expect, it } from 'vitest';
import { ApiError, createApiClient } from './runtime';
import { operationTable } from './generated';

interface Seen {
  url: string;
  init: RequestInit;
}

function fakeFetch(status: number, body: unknown, headers: Record<string, string> = {}) {
  const seen: Seen[] = [];
  const impl = (url: string | URL | Request, init?: RequestInit) => {
    seen.push({ url: String(url), init: init ?? {} });
    const isProblem = status >= 400;
    return Promise.resolve(
      new Response(body === undefined ? null : JSON.stringify(body), {
        status,
        headers: {
          'content-type': isProblem ? 'application/problem+json' : 'application/json',
          ...headers,
        },
      }),
    );
  };
  return { impl: impl as typeof fetch, seen };
}

const ME = {
  subject: 's',
  issuer: 'i',
  tenant_id: '00000000-0000-4000-8000-000000000001',
  tenant_name: 'A',
  roles: [],
  service_scope: null,
  session_id: '00000000-0000-4000-8000-000000000002',
  mfa: true,
  tenants: [],
};

describe('generierter API-Client', () => {
  it('die Operationstabelle stammt aus dem Vertrag (getMe, switchTenant, revokeMembership)', () => {
    expect(operationTable.getMe).toMatchObject({
      method: 'GET',
      path: '/v1/me',
      scopes: ['me:read'],
    });
    expect(operationTable.switchTenant.headers).toContain('Idempotency-Key');
    expect(operationTable.revokeMembership.headers).toContain('If-Match');
    expect(operationTable.revokeMembership.statuses).toContain(412);
  });

  it('ruft getMe mit Bearer-Token aus dem Arbeitsspeicher und liefert den typisierten Körper', async () => {
    const f = fakeFetch(200, ME);
    const api = createApiClient({
      baseUrl: 'https://api.example',
      fetch: f.impl,
      getToken: () => 'tok',
    });
    const me = await api.call('getMe', {});
    expect(me.tenant_name).toBe('A');
    expect(f.seen[0]?.url).toBe('https://api.example/v1/me');
    expect(new Headers(f.seen[0]?.init.headers).get('authorization')).toBe('Bearer tok');
    expect(f.seen[0]?.init.credentials).toBe('omit');
  });

  it('setzt Pfad- und Query-Parameter kodiert ein und erzeugt Idempotency-Key und If-Match', async () => {
    const f = fakeFetch(204, undefined);
    const api = createApiClient({ baseUrl: '', fetch: f.impl, newKey: () => 'key-1' });
    await api.call('revokeMembership', { path: { membership_id: 'a/b' }, revision: 7 });
    const h = new Headers(f.seen[0]?.init.headers);
    expect(f.seen[0]?.url).toBe('/v1/memberships/a%2Fb');
    expect(h.get('idempotency-key')).toBe('key-1');
    expect(h.get('if-match')).toBe('"7"');
    await api.call('listMemberships', { query: { limit: 10, cursor: 'x y' } });
    expect(f.seen[1]?.url).toBe('/v1/memberships?limit=10&cursor=x+y');
  });

  it('wirft ApiError mit Problem, Statuscode und Retry-After; der freie Text wird nicht ausgewertet', async () => {
    const problem = {
      type: 'https://api.taktovia.example/problems/rate-limited',
      title: 'Zu viele Anfragen',
      status: 429,
      code: 'RATE_LIMITED',
      correlation_id: '00000000-0000-4000-8000-000000000070',
    };
    const f = fakeFetch(429, problem, { 'retry-after': '12' });
    const api = createApiClient({ baseUrl: '', fetch: f.impl });
    const err = await api.call('getMe', {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    expect(e.status).toBe(429);
    expect(e.problem?.code).toBe('RATE_LIMITED');
    expect(e.retryAfterSeconds).toBe(12);
  });

  it('ein Fehlerkörper ohne Problem-JSON ergibt trotzdem einen ApiError mit Status', async () => {
    const impl = (() =>
      Promise.resolve(new Response('<html>', { status: 502 }))) as unknown as typeof fetch;
    const api = createApiClient({ baseUrl: '', fetch: impl });
    const e = (await api.call('getMe', {}).catch((x: unknown) => x)) as ApiError;
    expect(e.status).toBe(502);
    expect(e.problem).toBeUndefined();
  });

  it('ein Netzwerkfehler wird als ApiError mit Status 0 gemeldet', async () => {
    const impl = (() => Promise.reject(new TypeError('offline'))) as unknown as typeof fetch;
    const api = createApiClient({ baseUrl: '', fetch: impl });
    const e = (await api.call('getMe', {}).catch((x: unknown) => x)) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(0);
  });

  it('callBlob_laedt_binaere_antworten_mit_token_und_meldet_fehler_wie_call', async () => {
    const seen: Seen[] = [];
    const bytes = new Uint8Array([0xff, 0xd8, 0xff]);
    const impl = (url: string | URL | Request, init?: RequestInit) => {
      seen.push({ url: String(url), init: init ?? {} });
      return Promise.resolve(
        seen.length === 1
          ? new Response(bytes, { status: 200, headers: { 'content-type': 'image/jpeg' } })
          : new Response(
              JSON.stringify({
                type: 'about:blank',
                title: 'x',
                status: 404,
                code: 'NOT_FOUND',
                correlation_id: 'c',
              }),
              {
                status: 404,
                headers: { 'content-type': 'application/problem+json' },
              },
            ),
      );
    };
    const client = createApiClient({
      baseUrl: 'https://api.test',
      fetch: impl as typeof fetch,
      getToken: () => 'tok',
    });
    const id = '00000000-0000-4000-8000-0000000000e1';
    const r = await client.callBlob?.('getExpenseOriginal', { path: { expense_id: id } });
    expect(r?.mime).toBe('image/jpeg');
    expect(r?.blob.size).toBe(3);
    expect(seen[0]?.url).toBe(`https://api.test/v1/expenses/${id}/original`);
    expect(new Headers(seen[0]?.init.headers).get('authorization')).toBe('Bearer tok');
    expect(new Headers(seen[0]?.init.headers).get('accept')).toBe('*/*');
    await expect(
      client.callBlob?.('getExpenseOriginal', { path: { expense_id: id } }),
    ).rejects.toMatchObject({ status: 404 });
  });
});
