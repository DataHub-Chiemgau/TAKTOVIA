import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import { ApiError, createApiClient } from '../runtime';
import type { ApiClient, ApiClientOptions } from '../runtime';
import {
  buildAuthorizationUrl,
  completeLogin,
  endSessionUrl,
  isCallback,
  refreshTokens,
} from './oidc';
import type { OidcConfig, PendingStore, TokenSet } from './oidc';

export type EndReason = 'expired' | 'revoked' | 'mfaRequired' | 'error';

/**
 * Zustand der Anmeldung dieses Tabs. `ended` ist der Zustand nach einem serverseitigen Ende: `expired` (Token abgelaufen, Erneuerung nicht
 * möglich), `revoked` (Sitzung vom Server beendet oder gesperrt: 401 trotz gültig aussehendem Token), `mfaRequired` (Step-up verlangt),
 * `error` (Anmeldung fehlgeschlagen).
 */
export type AuthState =
  | { status: 'signedOut' }
  | { status: 'signingIn' }
  | { status: 'authenticated' }
  | { status: 'ended'; reason: EndReason };

export interface AuthConfig {
  oidc: OidcConfig;
  baseUrl: string;
  /** Administrator-Anwendungen fordern den zweiten Faktor (`acr_values=loa2`) bereits bei der Anmeldung an. */
  requestMfa?: boolean;
  /** Tabgebundener Speicher (Standard: Sitzungsspeicher des Browsers); nie ein tabübergreifender Speicher, damit Tabs ihren Kontext nicht teilen. */
  storage?: PendingStore;
  fetch?: typeof fetch;
  now?: () => number;
  /** Seitenwechsel (Standard: `window.location.assign`); austauschbar für Tests. */
  navigate?: (url: string) => void;
  /** Aktuelle Adresse (Standard: `window.location`); austauschbar für Tests. */
  location?: () => { search: string; pathname: string };
  /** Rückkehradresse bereinigen (Standard: `history.replaceState`). */
  replaceUrl?: (path: string) => void;
}

export interface AuthApi {
  state: AuthState;
  /** Meldet über den Anbieter an (Weiterleitung). `mfa` verlangt den zweiten Faktor. */
  signIn: (opts?: { mfa?: boolean }) => Promise<void>;
  /** Beendet die lokale Anmeldung dieses Tabs und führt optional zum Abmelden beim Anbieter. */
  signOut: (opts?: { provider?: boolean }) => void;
  /** API-Client dieses Tabs mit Token (nur Arbeitsspeicher), Mandantenbindung und Behandlung von 401/403. */
  client: ApiClient;
  /** Setzt oder löscht die Mandantenbindung dieses Tabs (Antwort des Tenantwechsels). */
  setContext: (ticket: string | undefined) => void;
}

/** Tokens und Mandantenbindung dieses Tabs im Arbeitsspeicher; Zugriff nur über Methoden. */
class Holder {
  tokens: TokenSet | undefined;
  ticket: string | undefined;
  setTokens(t: TokenSet | undefined) {
    this.tokens = t;
  }
  setTicket(t: string | undefined) {
    this.ticket = t;
  }
}

const CONTEXT_KEY = 'taktovia.ctx';
const REFRESH_MARGIN_MS = 30_000;
const Ctx = createContext<AuthApi | null>(null);

function defaultStorage(): PendingStore | undefined {
  try {
    return window.sessionStorage;
  } catch {
    return undefined;
  }
}

function memoryStore(): PendingStore {
  const m = new Map<string, string>();
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, v),
    removeItem: (k) => void m.delete(k),
  };
}

/**
 * Anmeldung im Browser (OIDC Authorization Code mit PKCE, WP-M1-44). **Tokenhaltung:** Zugriffs- und Erneuerungstoken nur im
 * Arbeitsspeicher dieses Tabs (kein Web-Speicher, keine Cookies, `credentials: 'omit'`); ein Neuladen meldet über die SSO-Sitzung des Anbieters
 * neu an. **Kontext je Tab:** die Mandantenbindung liegt je Tab im `sessionStorage` und wird nie geteilt; sie verleiht keine Rechte.
 * **Serverende:** ein 401 trotz gültigem Token (Sitzung beendet oder Konto gesperrt) führt nach einem Erneuerungsversuch zur Abmeldung.
 */
export function AuthProvider({ config, children }: { config: AuthConfig; children: ReactNode }) {
  const cfgRef = useRef(config);
  const now = config.now ?? Date.now;
  const storage = useMemo(
    () => config.storage ?? defaultStorage() ?? memoryStore(),
    [config.storage],
  );
  // Rückkehr vom Anbieter (Code in der Adresse) beginnt direkt im Zustand „Anmeldung läuft“
  const [state, setState] = useState<AuthState>(() =>
    isCallback((config.location ?? (() => window.location))().search)
      ? { status: 'signingIn' }
      : { status: 'signedOut' },
  );
  // Veränderlicher Halter für Tokens und Mandantenbindung dieses Tabs (kein React-Zustand: Änderungen lösen kein Rendern aus)
  const [h] = useState(() => new Holder());
  const refreshing = useRef<Promise<boolean> | undefined>(undefined);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const started = useRef(false);

  const readTicket = useCallback((): string | undefined => {
    try {
      return storage.getItem(CONTEXT_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  }, [storage]);

  const setContext = useCallback(
    (value: string | undefined) => {
      h.setTicket(value);
      try {
        if (value) storage.setItem(CONTEXT_KEY, value);
        else storage.removeItem(CONTEXT_KEY);
      } catch {
        /* Speicher nicht verfügbar: Kontext gilt nur im Arbeitsspeicher */
      }
    },
    [h, storage],
  );

  const clear = useCallback(() => {
    h.setTokens(undefined);
    if (timer.current) clearTimeout(timer.current);
    timer.current = undefined;
    setContext(undefined);
  }, [h, setContext]);

  const end = useCallback(
    (reason: EndReason) => {
      clear();
      setState({ status: 'ended', reason });
    },
    [clear],
  );

  const schedule = useCallback(
    (set: TokenSet, refresh: () => Promise<boolean>) => {
      if (timer.current) clearTimeout(timer.current);
      if (!set.refreshToken) return;
      const delay = Math.max(set.expiresAt - now() - REFRESH_MARGIN_MS, 1_000);
      timer.current = setTimeout(() => void refresh(), delay);
    },
    [now],
  );

  /** Erneuert die Tokens (einmal gleichzeitig). `false`: nicht möglich (Sitzung im IdP beendet, kein Erneuerungstoken, Netzwerk). */
  const refresh = useCallback((): Promise<boolean> => {
    const current = h.tokens;
    if (!current?.refreshToken) return Promise.resolve(false);
    refreshing.current ??= refreshTokens(
      { ...cfgRef.current.oidc, ...(cfgRef.current.fetch ? { fetch: cfgRef.current.fetch } : {}) },
      current.refreshToken,
      now(),
    )
      .then((next) => {
        const merged = { ...next, refreshToken: next.refreshToken ?? current.refreshToken };
        h.setTokens(merged);
        schedule(merged, refresh);
        return true;
      })
      .catch(() => false)
      .finally(() => {
        refreshing.current = undefined;
      });
    return refreshing.current;
  }, [h, now, schedule]);

  // Rückkehr vom Anbieter einlösen; genau einmal je Seitenaufruf (StrictMode ruft Effekte doppelt)
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const loc = (config.location ?? (() => window.location))();
    h.setTicket(readTicket());
    if (!isCallback(loc.search)) return;
    const oidc = { ...config.oidc, ...(config.fetch ? { fetch: config.fetch } : {}) };
    completeLogin(oidc, storage, loc.search, now())
      .then(({ tokens: t, returnTo }) => {
        h.setTokens(t);
        schedule(t, refresh);
        (config.replaceUrl ?? ((p: string) => window.history.replaceState(null, '', p)))(returnTo);
        setState({ status: 'authenticated' });
      })
      .catch(() => {
        (config.replaceUrl ?? ((p: string) => window.history.replaceState(null, '', p)))('/');
        end('error');
      });
  }, [config, end, h, now, readTicket, refresh, schedule, storage]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const signIn = useCallback(
    async (opts?: { mfa?: boolean }) => {
      const cfg = cfgRef.current;
      const loc = (cfg.location ?? (() => window.location))();
      const acr = opts?.mfa || cfg.requestMfa ? 'loa2' : undefined;
      const url = await buildAuthorizationUrl(
        { ...cfg.oidc, ...(cfg.fetch ? { fetch: cfg.fetch } : {}) },
        storage,
        { acr, returnTo: loc.pathname },
      );
      setState({ status: 'signingIn' });
      (cfg.navigate ?? ((u: string) => window.location.assign(u)))(url);
    },
    [storage],
  );

  const signOut = useCallback(
    (opts?: { provider?: boolean }) => {
      clear();
      setState({ status: 'signedOut' });
      const url = opts?.provider === false ? undefined : endSessionUrl(cfgRef.current.oidc);
      if (url) (cfgRef.current.navigate ?? ((u: string) => window.location.assign(u)))(url);
    },
    [clear],
  );

  const client = useMemo<ApiClient>(() => {
    const options: ApiClientOptions = {
      baseUrl: config.baseUrl,
      getToken: () => h.tokens?.accessToken,
      getContext: () => h.ticket,
      ...(config.fetch ? { fetch: config.fetch } : {}),
    };
    const base = createApiClient(options);
    const call: ApiClient['call'] = async (id, args) => {
      if (!h.tokens) throw new ApiError(401);
      // abgelaufenes Zugriffstoken zuerst erneuern; gelingt das nicht, ist die Sitzung abgelaufen
      if (h.tokens.expiresAt - now() < 5_000 && !(await refresh())) {
        end('expired');
        throw new ApiError(401);
      }
      const attempt = () => base.call(id, args);
      try {
        return await attempt();
      } catch (e) {
        if (!(e instanceof ApiError)) throw e;
        if (e.status === 401) {
          // Sitzung vom Server beendet oder gesperrt? Ein Erneuerungsversuch scheitert dann im IdP oder liefert erneut 401.
          const wasExpired = (h.tokens?.expiresAt ?? 0) <= now();
          if (await refresh()) {
            try {
              return await attempt();
            } catch (e2) {
              if (e2 instanceof ApiError && e2.status === 401) end('revoked');
              throw e2;
            }
          }
          end(wasExpired ? 'expired' : 'revoked');
          throw e;
        }
        if (e.status === 403 && /insufficient_user_authentication/.test(e.wwwAuthenticate ?? '')) {
          end('mfaRequired');
          throw e;
        }
        // Ungültige oder abgelaufene Mandantenbindung (z. B. manipulierter Speicher) ergibt 403: Bindung verwerfen, Standardmandant laden.
        if (e.status === 403 && id === 'getMe' && h.ticket) {
          setContext(undefined);
          return base.call(id, args);
        }
        throw e;
      }
    };
    return { call };
  }, [config.baseUrl, config.fetch, end, h, now, refresh, setContext]);

  const api = useMemo<AuthApi>(
    () => ({ state, signIn, signOut, client, setContext }),
    [state, signIn, signOut, client, setContext],
  );
  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthApi {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth außerhalb von AuthProvider');
  return v;
}

/** Wie [useAuth], aber `null` ohne Provider (Bildschirme, die auch ohne Anmeldungsrahmen laufen, z. B. in Tests). */
export function useAuthOptional(): AuthApi | null {
  return useContext(Ctx);
}
