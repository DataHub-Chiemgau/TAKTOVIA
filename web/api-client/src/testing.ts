import type { Me, OperationId, Operations } from './generated';
import { ApiError } from './runtime';
import type { ApiClient } from './runtime';

type Handler<K extends OperationId> = () => Operations[K]['response'] | ApiError;

/** Test-Client mit festen Antworten je operationId; Fehler werden als `ApiError` geliefert. Nicht für Produktionscode. */
export function fakeApiClient(handlers: { [K in OperationId]?: Handler<K> }): ApiClient {
  return {
    call(id) {
      const handler = handlers[id] as (() => unknown) | undefined;
      if (!handler) return Promise.reject(new ApiError(404));
      const result = handler();
      return result instanceof ApiError ? Promise.reject(result) : Promise.resolve(result as never);
    },
  };
}

export function sampleMe(overrides: Partial<Me> = {}): Me {
  return {
    subject: 'user-1',
    issuer: 'https://idp.example/realms/test',
    tenant_id: '00000000-0000-4000-8000-000000000001',
    tenant_name: 'Verkehrsbetrieb Nord',
    roles: [],
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Ersatz für IdP und Server in Web-Tests (WP-M1-44). Beides sind Modelle des dokumentierten Verhaltens, kein Ersatz für die echten
// Komponenten: der echte Keycloak-Login und der Browserablauf sind in dieser Umgebung NICHT ausgeführt; das Serververhalten (401 bei
// gesperrter Sitzung, Mandantenbindung, 403 ohne Mitgliedschaft) prüfen die Backend-Tests gegen die echte Anwendung.
// ---------------------------------------------------------------------------------------------------------------------------------

function b64url(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function s256(verifier: string): Promise<string> {
  return b64url(
    new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))),
  );
}

export interface MockIdpUser {
  subject: string;
}

/**
 * Lokaler OIDC-Anbieter (Authorization Code mit PKCE) als `fetch`-Ersatz: prüft `code_verifier` gegen die S256-Challenge, gibt Code und Tokens
 * nur einmal aus, kennt beendete Sitzungen (Erneuerung scheitert dann mit `invalid_grant`).
 */
export function createMockIdp(opts: { tokenEndpoint?: string; accessTtlSeconds?: number } = {}) {
  const tokenEndpoint =
    opts.tokenEndpoint ?? 'https://idp.test/realms/t/protocol/openid-connect/token';
  const codes = new Map<string, { challenge: string; subject: string; acr: string | undefined }>();
  const refreshTokens = new Map<
    string,
    { subject: string; sid: string; acr: string | undefined }
  >();
  const endedSessions = new Set<string>();
  const issued: { access: string[]; refresh: string[] } = { access: [], refresh: [] };
  const exchanges: string[] = [];
  let n = 0;
  const idp = {
    tokenEndpoint,
    issued,
    exchanges,
    /** Zuordnung Zugriffstoken -> Sitzung für den Server-Ersatz. */
    sessions: new Map<string, { subject: string; sid: string; acr: string | undefined }>(),
    /** Simuliert die Anmeldung des Nutzers am Anbieter und liefert die Rückkehradresse (`?code=…&state=…`). */
    authorize(authUrl: string, subject: string): string {
      const u = new URL(authUrl);
      const code = `code-${++n}`;
      codes.set(code, {
        challenge: u.searchParams.get('code_challenge') ?? '',
        subject,
        acr: u.searchParams.get('acr_values') ?? undefined,
      });
      return `?code=${code}&state=${u.searchParams.get('state') ?? ''}`;
    },
    /** Beendet die Sitzung im Anbieter: Erneuerung ist danach unmöglich. */
    endSession(sid: string) {
      endedSessions.add(sid);
    },
    fetch: (async (url: string | URL | Request, init?: RequestInit) => {
      if (String(url) !== tokenEndpoint) return new Response('', { status: 404 });
      const form = new URLSearchParams(String(init?.body));
      const grant = form.get('grant_type');
      const json = (body: unknown, status = 200) =>
        new Response(JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json' },
        });
      const mint = (subject: string, sid: string, acr: string | undefined) => {
        const access = `at-${subject}-${sid}-${++n}`;
        const refresh = `rt-${subject}-${sid}-${n}`;
        issued.access.push(access);
        issued.refresh.push(refresh);
        idp.sessions.set(access, { subject, sid, acr });
        refreshTokens.set(refresh, { subject, sid, acr });
        return json({
          access_token: access,
          refresh_token: refresh,
          expires_in: opts.accessTtlSeconds ?? 300,
          token_type: 'Bearer',
        });
      };
      if (grant === 'authorization_code') {
        const code = form.get('code') ?? '';
        const entry = codes.get(code);
        codes.delete(code); // einmalig einlösbar
        const verifier = form.get('code_verifier');
        exchanges.push(code);
        if (!entry || !verifier || (await s256(verifier)) !== entry.challenge)
          return json({ error: 'invalid_grant' }, 400);
        return mint(entry.subject, `sid-${entry.subject}-${n + 1}`, entry.acr);
      }
      if (grant === 'refresh_token') {
        const entry = refreshTokens.get(form.get('refresh_token') ?? '');
        if (!entry || endedSessions.has(entry.sid)) return json({ error: 'invalid_grant' }, 400);
        return mint(entry.subject, entry.sid, entry.acr);
      }
      return json({ error: 'unsupported_grant_type' }, 400);
    }) as typeof fetch,
  };
  return idp;
}

export interface FakeTenant {
  id: string;
  name: string;
  roles: string[];
  /** Wirksame Funktionsscopes im Mandanten (Standard: Verwaltungsscopes). */
  scopes?: string[];
}

interface FakeSession {
  id: string;
  subject: string;
  created_at: string;
  last_seen_at: string;
  revoked_at: string | null;
  revision: number;
}

/**
 * Server-Ersatz mit dem dokumentierten Verhalten: Mandant nur aus Mitgliedschaft und signierter Bindung (`X-Taktovia-Context`), 403 ohne
 * Mitgliedschaft oder bei ungültiger Bindung, 401 bei gesperrter Sitzung trotz gültigem Token, Sitzungsliste, Abmelden einzelner Geräte und
 * aller Geräte. `memberships` sind je Mandant getrennte Zeilen; Schreibaktionen landen im Mandanten der Bindung.
 */
export function createFakeApi(
  idp: ReturnType<typeof createMockIdp>,
  directory: Record<string, FakeTenant[]>,
) {
  const sessions = new Map<string, FakeSession>();
  const rows = new Map<string, string[]>();
  const log: { method: string; path: string; tenant: string | undefined; status: number }[] = [];
  const revokedSids = new Set<string>();
  let mfaRequired = false;
  const api = {
    log,
    /** Sperrt eine Sitzung serverseitig (Administrator oder „alle Geräte“): jedes Token dieser Sitzung ergibt danach 401. */
    revokeSid(sid: string) {
      revokedSids.add(sid);
      for (const s of sessions.values()) if (s.id === sid) s.revoked_at = '2026-11-02T08:00:00Z';
    },
    /** Verlangt für alle Aufrufe den zweiten Faktor (403 mit `insufficient_user_authentication`). */
    requireMfa(value: boolean) {
      mfaRequired = value;
    },
    rowsOf(tenantId: string) {
      return rows.get(tenantId) ?? [];
    },
    /** Legt Mitgliedschaftszeilen eines Mandanten vor. */
    seedRows(tenantId: string, subjects: string[]) {
      rows.set(tenantId, [...(rows.get(tenantId) ?? []), ...subjects]);
    },
    fetch: (async (url: string | URL | Request, init?: RequestInit) => {
      const u = new URL(String(url), 'https://api.test');
      const method = (init?.method ?? 'GET').toUpperCase();
      const headers = new Headers(init?.headers);
      const respond = (
        status: number,
        body?: unknown,
        extra: Record<string, string> = {},
        tenant?: string,
      ) => {
        log.push({ method, path: u.pathname, tenant, status });
        return new Response(body === undefined ? null : JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json', ...extra },
        });
      };
      const problem = (status: number, code: string, extra: Record<string, string> = {}) =>
        respond(status, { status, code, type: code, title: code }, extra);
      const bearer = headers.get('authorization')?.replace(/^Bearer /, '') ?? '';
      const session = idp.sessions.get(bearer);
      if (!session) return problem(401, 'UNAUTHENTICATED');
      if (revokedSids.has(session.sid)) return problem(401, 'UNAUTHENTICATED'); // gesperrte Sitzung trotz gültigem Token
      if (mfaRequired && session.acr !== 'loa2')
        return problem(403, 'FORBIDDEN', {
          'www-authenticate': 'Bearer error="insufficient_user_authentication", acr_values="loa2"',
        });
      const memberships = [...(directory[session.subject] ?? [])].sort((a, b) =>
        a.id.localeCompare(b.id),
      );
      if (memberships.length === 0) return problem(403, 'FORBIDDEN'); // keine Mitgliedschaft: keine Daten
      const ticket = headers.get('x-taktovia-context');
      let active: FakeTenant | undefined;
      if (ticket) {
        const m = /^ticket\.(.+)\.(.+)\.(.+)$/.exec(ticket);
        active =
          m && m[1] === session.subject && m[3] === session.sid
            ? memberships.find((t) => t.id === m[2])
            : undefined;
        if (!active) return problem(403, 'FORBIDDEN'); // gefälschte oder fremde Bindung: keine Rechte
      } else active = memberships[0];
      if (!active) return problem(403, 'FORBIDDEN');
      const tenant = active.id;
      const me = () => ({
        subject: session.subject,
        issuer: 'https://idp.test/realms/t',
        tenant_id: tenant,
        tenant_name: active.name,
        roles: active.roles,
        scopes: active.scopes ?? [
          'me:read',
          'me:write',
          'tenant:switch',
          'memberships:read',
          'memberships:write',
        ],
        service_scope: null,
        session_id: session.sid,
        mfa: session.acr === 'loa2',
        tenants: memberships.map((t) => ({ tenant_id: t.id, name: t.name })),
      });
      const own = () => {
        const key = `${tenant}|${session.sid}`;
        if (!sessions.has(key))
          sessions.set(key, {
            id: session.sid,
            subject: session.subject,
            created_at: '2026-11-02T07:00:00Z',
            last_seen_at: '2026-11-02T07:30:00Z',
            revoked_at: null,
            revision: 1,
          });
        return sessions.get(key) as FakeSession;
      };
      if (u.pathname === '/v1/me' && method === 'GET') return respond(200, me(), {}, tenant);
      if (u.pathname === '/v1/me/tenant-switch' && method === 'POST') {
        const wanted = (JSON.parse(String(init?.body)) as { tenant_id: string }).tenant_id;
        if (!memberships.some((t) => t.id === wanted)) return problem(403, 'FORBIDDEN');
        const target = memberships.find((t) => t.id === wanted) as FakeTenant;
        return respond(
          200,
          {
            ...me(),
            tenant_id: target.id,
            tenant_name: target.name,
            roles: target.roles,
            context_token: `ticket.${session.subject}.${target.id}.${session.sid}`,
          },
          {},
          target.id,
        );
      }
      if (u.pathname === '/v1/memberships' && method === 'GET')
        return respond(
          200,
          {
            items: api.rowsOf(tenant).map((s, i) => ({
              id: `00000000-0000-4000-8000-00000000${String(i).padStart(4, '0')}`,
              issuer: 'i',
              subject: s,
              role_code: 'support',
              service_scope: null,
              valid_from: '2026-01-01T00:00:00Z',
              valid_to: null,
              revoked_at: null,
              revision: 1,
            })),
            next_cursor: null,
            as_of: '2026-11-02T08:00:00Z',
          },
          {},
          tenant,
        );
      if (u.pathname === '/v1/memberships' && method === 'POST') {
        const body = JSON.parse(String(init?.body)) as { subject: string };
        rows.set(tenant, [...api.rowsOf(tenant), body.subject]);
        return respond(
          201,
          {
            id: '00000000-0000-4000-8000-000000009999',
            issuer: 'i',
            subject: body.subject,
            role_code: 'support',
            service_scope: null,
            valid_from: '2026-01-01T00:00:00Z',
            valid_to: null,
            revoked_at: null,
            revision: 1,
          },
          {},
          tenant,
        );
      }
      if (u.pathname === '/v1/me/sessions' && method === 'GET') {
        own();
        return respond(
          200,
          {
            items: [...sessions.entries()]
              .filter(([k, s]) => k.startsWith(`${tenant}|`) && s.subject === session.subject)
              .map(([, s]) => s),
            next_cursor: null,
            as_of: '2026-11-02T08:00:00Z',
          },
          {},
          tenant,
        );
      }
      if (u.pathname === '/v1/sessions' && method === 'GET') {
        own();
        return respond(
          200,
          {
            items: [...sessions.entries()]
              .filter(([k]) => k.startsWith(`${tenant}|`))
              .map(([, v]) => v),
            next_cursor: null,
            as_of: '2026-11-02T08:00:00Z',
          },
          {},
          tenant,
        );
      }
      const del = /^\/v1\/me\/sessions\/(.+)$/.exec(u.pathname);
      if (del && method === 'DELETE') {
        const target = [...sessions.values()].find(
          (s) => s.id === del[1] && s.subject === session.subject,
        );
        if (!target) return problem(404, 'NOT_FOUND');
        api.revokeSid(target.id);
        idp.endSession(target.id);
        return respond(204, undefined, {}, tenant);
      }
      if (u.pathname === '/v1/me/sessions/revoke-all' && method === 'POST') {
        for (const s of sessions.values()) if (s.subject === session.subject) api.revokeSid(s.id);
        api.revokeSid(session.sid);
        idp.endSession(session.sid);
        return respond(204, undefined, {}, tenant);
      }
      return problem(404, 'NOT_FOUND');
    }) as typeof fetch,
  };
  return api;
}

/** Zugriffstoken, die der Anmeldungsbaustein gerade im Arbeitsspeicher hält, sind nie im Speicher; Hilfsfunktion für Prüfungen. */
export function storageDump(store: {
  length: number;
  key(i: number): string | null;
  getItem(k: string): string | null;
}): string {
  const out: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const k = store.key(i);
    if (k !== null) out.push(`${k}=${store.getItem(k) ?? ''}`);
  }
  return out.join('\n');
}
