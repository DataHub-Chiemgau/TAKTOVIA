import { useCallback, useEffect, useState } from 'react';
import type { Session as ApiSession } from '../generated';
import { ApiError } from '../runtime';
import { useApi, useSession } from '../session';
import { useAuthOptional } from './AuthProvider';
import { useDirtyForms } from './drafts';

export interface TenantSwitchState {
  pending: boolean;
  error: ApiError | undefined;
  /**
   * Wechselt den Mandanten **dieses Tabs**: der Server prüft die Mitgliedschaft und liefert die signierte Mandantenbindung, die nur dieser Tab
   * nutzt (andere Tabs behalten ihren Kontext). Danach wird die Sitzung neu geladen; der Bildschirmbaum des alten Mandanten wird abgebaut.
   */
  switchTo: (tenantId: string) => Promise<boolean>;
}

export function useTenantSwitch(): TenantSwitchState {
  const api = useApi();
  const auth = useAuthOptional();
  const session = useSession();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | undefined>(undefined);
  const reload =
    session.status === 'ready' || session.status === 'error' ? session.reload : undefined;
  const switchTo = useCallback(
    async (tenantId: string) => {
      setPending(true);
      setError(undefined);
      try {
        const me = await api.call('switchTenant', { body: { tenant_id: tenantId } });
        if (!me.context_token) throw new ApiError(0);
        auth?.setContext(me.context_token);
        reload?.();
        return true;
      } catch (e) {
        setError(e instanceof ApiError ? e : new ApiError(0));
        return false;
      } finally {
        setPending(false);
      }
    },
    [api, auth, reload],
  );
  return { pending, error, switchTo };
}

export type MySessionsState =
  | { status: 'loading' }
  | { status: 'error'; error: ApiError }
  | { status: 'ready'; items: ApiSession[] };

export interface MySessions {
  state: MySessionsState;
  /** Kennung der Sitzung dieses Geräts (aus `/v1/me`), um sie in der Liste zu erkennen. */
  currentId: string | undefined;
  notice: string | undefined;
  busy: boolean;
  reload: () => void;
  /** Meldet ein Gerät ab; die eigene Sitzung beendet zusätzlich die Anmeldung dieses Tabs. */
  revoke: (s: ApiSession) => Promise<void>;
  /** „Alle Geräte abmelden“: beendet alle Sitzungen des Kontos und die Anmeldung dieses Tabs. */
  revokeAll: () => Promise<void>;
}

/** UI-05 Basis: eigene Sitzungen im aktiven Mandanten, einzelne Geräte abmelden, alle Geräte abmelden. */
export function useMySessions(): MySessions {
  const api = useApi();
  const auth = useAuthOptional();
  const session = useSession();
  const [state, setState] = useState<MySessionsState>({ status: 'loading' });
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);
  const currentId = session.status === 'ready' ? session.me.session_id : undefined;

  useEffect(() => {
    let cancelled = false;
    api
      .call('listMySessions', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setState({ status: 'ready', items: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : new ApiError(0) });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const reload = useCallback(() => {
    setState({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  const revoke = useCallback(
    async (s: ApiSession) => {
      setBusy(true);
      setNotice(undefined);
      try {
        await api.call('revokeMySession', { path: { session_id: s.id }, revision: s.revision });
        if (s.id === currentId) auth?.signOut();
        else {
          setNotice('Das Gerät wurde abgemeldet.');
          reload();
        }
      } catch (e) {
        setNotice(
          e instanceof ApiError && e.status === 503
            ? 'Das Gerät ist lokal abgemeldet; der Identitätsanbieter hat es noch nicht bestätigt. Bitte „Alle Geräte abmelden“ wiederholen.'
            : 'Das Gerät konnte nicht abgemeldet werden. Die Liste wurde neu geladen.',
        );
        reload();
      } finally {
        setBusy(false);
      }
    },
    [api, auth, currentId, reload],
  );

  const revokeAll = useCallback(async () => {
    setBusy(true);
    setNotice(undefined);
    try {
      await api.call('revokeAllMySessions', {});
      auth?.signOut();
    } catch (e) {
      // 503: lokal gesperrt, IdP nicht bestätigt; der nächste Aufruf liefert 401 und der Rahmen zeigt den Zustand „Sitzung beendet“
      setNotice(
        e instanceof ApiError && e.status === 503
          ? 'Die Sitzungen sind lokal beendet; der Identitätsanbieter hat es noch nicht bestätigt. Bitte erneut versuchen.'
          : 'Die Abmeldung auf allen Geräten ist fehlgeschlagen.',
      );
    } finally {
      setBusy(false);
    }
  }, [api, auth]);

  return { state, currentId, notice, busy, reload, revoke, revokeAll };
}

/**
 * Abmelden dieses Geräts: sperrt die eigene Sitzung am Server (Liste lesen, Revision verwenden, abmelden) und beendet danach die Anmeldung dieses
 * Tabs. Scheitert der Serveraufruf, bleibt die lokale Abmeldung trotzdem wirksam (der Server prüft jede Anfrage erneut).
 */
export function useSignOut(): () => Promise<void> {
  const api = useApi();
  const auth = useAuthOptional();
  const session = useSession();
  const id = session.status === 'ready' ? session.me.session_id : undefined;
  return useCallback(async () => {
    try {
      if (id) {
        const page = await api.call('listMySessions', { query: { limit: 100 } });
        const mine = page.items.find((s) => s.id === id);
        if (mine)
          await api.call('revokeMySession', { path: { session_id: id }, revision: mine.revision });
      }
    } catch {
      /* lokale Abmeldung gilt trotzdem */
    }
    auth?.signOut();
  }, [api, auth, id]);
}

export interface TenantSwitcherData {
  tenants: { id: string; name: string }[];
  activeId: string;
  onSwitch: (tenantId: string) => Promise<boolean>;
  dirtyForms: string[];
  pending: boolean;
  failed: boolean;
}

/** Daten für den Mandantenwähler aus der autorisierten Sitzung (`/v1/me`): wählbare Mandanten, aktiver Mandant, Rückfrage bei ungespeicherten Eingaben. */
export function useTenantSwitcherData(): TenantSwitcherData | undefined {
  const session = useSession();
  const { pending, error, switchTo } = useTenantSwitch();
  const dirtyForms = useDirtyForms();
  if (session.status !== 'ready') return undefined;
  return {
    tenants: session.me.tenants.map((t) => ({ id: t.tenant_id, name: t.name })),
    activeId: session.me.tenant_id,
    onSwitch: switchTo,
    dirtyForms,
    pending,
    failed: error !== undefined,
  };
}
