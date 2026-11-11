import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { Me } from './generated';
import { ApiError } from './runtime';
import type { ApiClient } from './runtime';

export type Session =
  | { status: 'loading' }
  | { status: 'error'; error: ApiError; reload: () => void }
  | { status: 'ready'; me: Me; loadedAt: number; reload: () => void };

const ApiContext = createContext<ApiClient | null>(null);
const SessionContext = createContext<Session>({ status: 'loading' });

/**
 * Lädt die autorisierte Sitzung (`/v1/me`) und stellt sie bereit. Der Kontext ist eine Anzeige der serverseitig geprüften Sitzung,
 * keine Rechtequelle (Kap. 06 §6.1); nichts davon wird in Web-Speicher abgelegt.
 */
export function SessionProvider({
  client,
  now = Date.now,
  children,
}: {
  client: ApiClient;
  now?: () => number;
  children: ReactNode;
}) {
  const [session, setSession] = useState<Session>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const reload = useCallback(() => {
    setSession({ status: 'loading' });
    setAttempt((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    client
      .call('getMe', {})
      .then((me) => {
        if (!cancelled) setSession({ status: 'ready', me, loadedAt: now(), reload });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setSession({
          status: 'error',
          error: e instanceof ApiError ? e : new ApiError(0),
          reload,
        });
      });
    return () => {
      cancelled = true;
    };
  }, [client, now, reload, attempt]);

  const api = useMemo(() => client, [client]);
  return (
    <ApiContext.Provider value={api}>
      <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
    </ApiContext.Provider>
  );
}

export function useSession(): Session {
  return useContext(SessionContext);
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) throw new Error('useApi außerhalb von SessionProvider');
  return client;
}
