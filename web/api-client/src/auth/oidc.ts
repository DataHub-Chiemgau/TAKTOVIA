import { challengeS256, randomString } from './pkce';

/** Endpunkte und Client des OIDC-Anbieters (Keycloak, Authorization Code mit PKCE; öffentlicher Client ohne Secret). */
export interface OidcConfig {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  endSessionEndpoint?: string;
  clientId: string;
  redirectUri: string;
  postLogoutRedirectUri?: string;
  scope?: string;
  fetch?: typeof fetch;
}

/** Konfiguration für Keycloak aus Realm-URL (`<host>/realms/<realm>`) und Client. */
export function keycloakConfig(issuer: string, clientId: string, origin: string): OidcConfig {
  const base = `${issuer.replace(/\/+$/, '')}/protocol/openid-connect`;
  return {
    authorizationEndpoint: `${base}/auth`,
    tokenEndpoint: `${base}/token`,
    endSessionEndpoint: `${base}/logout`,
    clientId,
    redirectUri: `${origin}/`,
    postLogoutRedirectUri: `${origin}/`,
    scope: 'openid',
  };
}

export interface TokenSet {
  accessToken: string;
  refreshToken: string | undefined;
  /** Ablauf des Zugriffstokens in ms seit Epoche (aus `expires_in`, nicht aus dem Token gelesen). */
  expiresAt: number;
}

export class OidcError extends Error {
  constructor(readonly code: string) {
    super(code);
    this.name = 'OidcError';
  }
}

/** Tabgebundener Sitzungsspeicher des Browsers nur für den Zustand zwischen Weiterleitung und Rückkehr. */
export interface PendingStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const PENDING = 'taktovia.oidc.pending';

interface Pending {
  state: string;
  verifier: string;
  returnTo: string;
}

/**
 * Baut die Autorisierungsanfrage (Code + PKCE S256) und legt `state` und Verifier tabgebunden ab. `acr` fordert die Stufe an
 * (`loa2` = zweiter Faktor, Step-up für Administratorrollen, RFC 9470).
 */
export async function buildAuthorizationUrl(
  cfg: OidcConfig,
  store: PendingStore,
  opts: { acr?: string | undefined; returnTo?: string | undefined } = {},
): Promise<string> {
  const state = randomString(16);
  const verifier = randomString(32);
  const pending: Pending = { state, verifier, returnTo: opts.returnTo ?? '/' };
  store.setItem(PENDING, JSON.stringify(pending));
  const url = new URL(cfg.authorizationEndpoint);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', cfg.clientId);
  url.searchParams.set('redirect_uri', cfg.redirectUri);
  url.searchParams.set('scope', cfg.scope ?? 'openid');
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', await challengeS256(verifier));
  url.searchParams.set('code_challenge_method', 'S256');
  if (opts.acr) url.searchParams.set('acr_values', opts.acr);
  return url.toString();
}

function parseTokens(body: unknown, now: number): TokenSet {
  if (!body || typeof body !== 'object') throw new OidcError('token_response_invalid');
  const b = body as Record<string, unknown>;
  const access = b['access_token'];
  if (typeof access !== 'string' || access === '') throw new OidcError('token_response_invalid');
  const expires = typeof b['expires_in'] === 'number' ? b['expires_in'] : 60;
  const refresh = b['refresh_token'];
  return {
    accessToken: access,
    refreshToken: typeof refresh === 'string' ? refresh : undefined,
    expiresAt: now + expires * 1000,
  };
}

async function tokenRequest(
  cfg: OidcConfig,
  form: Record<string, string>,
  now: number,
): Promise<TokenSet> {
  const doFetch = cfg.fetch ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  let res: Response;
  try {
    res = await doFetch(cfg.tokenEndpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: cfg.clientId, ...form }).toString(),
      credentials: 'omit',
    });
  } catch {
    throw new OidcError('network');
  }
  if (!res.ok)
    throw new OidcError(
      res.status === 400 || res.status === 401 ? 'invalid_grant' : 'token_endpoint_error',
    );
  return parseTokens(await res.json(), now);
}

/** Ob die Adresse eine Rückkehr vom Anbieter ist (Code oder Fehler plus `state`). */
export function isCallback(search: string): boolean {
  const p = new URLSearchParams(search);
  return p.has('state') && (p.has('code') || p.has('error'));
}

/**
 * Schließt die Anmeldung ab: prüft `state` gegen den tabgebundenen Wert (CSRF), löst den Code mit dem Verifier ein. Der Zustand wird
 * in jedem Fall entfernt (Code und Verifier sind einmalig). Ergebnis: Tokens nur für den Arbeitsspeicher und die Rücksprungadresse.
 */
export async function completeLogin(
  cfg: OidcConfig,
  store: PendingStore,
  search: string,
  now: number,
): Promise<{ tokens: TokenSet; returnTo: string }> {
  const raw = store.getItem(PENDING);
  store.removeItem(PENDING);
  const params = new URLSearchParams(search);
  let pending: Pending | undefined;
  try {
    pending = raw ? (JSON.parse(raw) as Pending) : undefined;
  } catch {
    pending = undefined;
  }
  if (!pending || params.get('state') !== pending.state) throw new OidcError('state_mismatch');
  if (params.has('error')) throw new OidcError(params.get('error') ?? 'error');
  const code = params.get('code');
  if (!code) throw new OidcError('code_missing');
  const tokens = await tokenRequest(
    cfg,
    {
      grant_type: 'authorization_code',
      code,
      redirect_uri: cfg.redirectUri,
      code_verifier: pending.verifier,
    },
    now,
  );
  return { tokens, returnTo: pending.returnTo.startsWith('/') ? pending.returnTo : '/' };
}

export function refreshTokens(
  cfg: OidcConfig,
  refreshToken: string,
  now: number,
): Promise<TokenSet> {
  return tokenRequest(cfg, { grant_type: 'refresh_token', refresh_token: refreshToken }, now);
}

export function endSessionUrl(cfg: OidcConfig): string | undefined {
  if (!cfg.endSessionEndpoint) return undefined;
  const url = new URL(cfg.endSessionEndpoint);
  url.searchParams.set('client_id', cfg.clientId);
  if (cfg.postLogoutRedirectUri)
    url.searchParams.set('post_logout_redirect_uri', cfg.postLogoutRedirectUri);
  return url.toString();
}
