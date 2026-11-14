import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
} from 'react';
import type { ReactNode } from 'react';
import { useAnnounce } from '../components/LiveRegion';
import {
  actionAvailability,
  deriveConnectionState,
  initialConnection,
  isServerRestricted,
  reduceConnection,
  stateTexts,
} from './connection';
import type {
  ActionAvailability,
  ConnectionModel,
  ConnectionState,
  NetworkAction,
} from './connection';

export interface ConnectionApi {
  state: ConnectionState;
  model: ConnectionModel;
  /** Die Einschränkungen des Offlinezustands wirken (offline oder Serverstand noch nicht geladen). */
  restricted: boolean;
  availability: (action: NetworkAction) => ActionAvailability;
  /** Eine Ansicht hat den Serverstand erfolgreich geladen (beendet nach einer Wiederverbindung den Offlinezustand). */
  serverStateLoaded: (at?: string) => void;
  /** Eine Anfrage ist am Netz gescheitert (`false`) oder gelungen (`true`): bei Misserfolg `limited`. */
  reportRequest: (ok: boolean) => void;
  setPending: (count: number) => void;
  setConflicts: (count: number) => void;
}

const fallback: ConnectionApi = {
  state: 'online',
  model: initialConnection,
  restricted: false,
  availability: (a) => actionAvailability(initialConnection, a),
  serverStateLoaded: () => undefined,
  reportRequest: () => undefined,
  setPending: () => undefined,
  setConflicts: () => undefined,
};

const Context = createContext<ConnectionApi>(fallback);

export interface ConnectionProviderProps {
  children: ReactNode;
  /** Quelle der Geräteverbindung (Standard `navigator.onLine` und die Fensterereignisse `online`/`offline`). */
  source?: ConnectionSource;
  /** Wartezeit, bevor ein Verbindungsverlust als `offline` gilt; kürzere Aussetzer lösen weder Anzeige noch Ansage aus (Standard 500 ms). */
  offlineDelayMs?: number;
  /**
   * Lädt nach einer Wiederverbindung den Serverstand (z. B. Sitzung und Buchungsliste). Erst nach dessen Erfolg endet der Offlinezustand; schlägt es fehl, bleibt der Zustand
   * `syncing` und es wird nach `retryMs` erneut versucht.
   */
  loadServerState?: () => Promise<void>;
  retryMs?: number;
  now?: () => number;
}

export interface ConnectionSource {
  isOnline: () => boolean;
  subscribe: (onChange: () => void) => () => void;
}

const browserSource: ConnectionSource = {
  isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine),
  subscribe: (onChange) => {
    window.addEventListener('online', onChange);
    window.addEventListener('offline', onChange);
    return () => {
      window.removeEventListener('online', onChange);
      window.removeEventListener('offline', onChange);
    };
  },
};

/**
 * Verbindungszustand für die ganze Anwendung (WP-M5-09). Liefert Zustand, Einschränkungsregeln und die Ansage über die Live-Region (`useAnnounce`): jeder Wechsel wird angesagt, mit
 * Text, nicht nur über Farbe. Kurze Aussetzer (< `offlineDelayMs`) ändern nichts; nach der Wiederverbindung bleibt der Zustand `syncing`, bis der Serverstand geladen ist.
 */
export function ConnectionProvider({
  children,
  source = browserSource,
  offlineDelayMs = 500,
  loadServerState,
  retryMs = 2000,
  now = Date.now,
}: ConnectionProviderProps) {
  const [model, dispatch] = useReducer(reduceConnection, initialConnection, (m) => ({
    ...m,
    linkUp: source.isOnline(),
    awaitingServerState: !source.isOnline(),
  }));
  const announce = useAnnounce();
  const loader = useRef(loadServerState);
  const nowRef = useRef(now);

  useEffect(() => {
    loader.current = loadServerState;
    nowRef.current = now;
  }, [loadServerState, now]);

  // Geräteverbindung mit Entprellung des Verlusts
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const apply = () => {
      if (source.isOnline()) {
        if (timer) clearTimeout(timer);
        timer = undefined;
        dispatch({ type: 'link', up: true });
      } else if (!timer) {
        timer = setTimeout(() => {
          timer = undefined;
          if (!source.isOnline()) dispatch({ type: 'link', up: false });
        }, offlineDelayMs);
      }
    };
    const unsubscribe = source.subscribe(apply);
    return () => {
      unsubscribe();
      if (timer) clearTimeout(timer);
    };
  }, [source, offlineDelayMs]);

  // Rückkehr: Serverstand laden (mit Wiederholung), erst danach endet der Offlinezustand
  const waiting = model.awaitingServerState && model.linkUp;
  useEffect(() => {
    if (!waiting) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attempt = () => {
      const load = loader.current;
      if (!load) return; // ohne Ladehaken beenden die Ansichten den Zustand mit `serverStateLoaded`
      load().then(
        () =>
          alive &&
          dispatch({ type: 'server-state-loaded', at: new Date(nowRef.current()).toISOString() }),
        () => {
          if (alive) timer = setTimeout(attempt, retryMs);
        },
      );
    };
    attempt();
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [waiting, retryMs]);

  const state = deriveConnectionState(model);
  const previous = useRef<ConnectionState>(state);
  useEffect(() => {
    if (previous.current === state) return;
    previous.current = state;
    const t = stateTexts[state];
    announce(`${t.label}. ${t.detail}`);
  }, [state, announce]);

  const serverStateLoaded = useCallback(
    (at?: string) =>
      dispatch({ type: 'server-state-loaded', at: at ?? new Date(nowRef.current()).toISOString() }),
    [],
  );
  const reportRequest = useCallback((ok: boolean) => dispatch({ type: 'limited', value: !ok }), []);
  const setPending = useCallback((count: number) => dispatch({ type: 'pending', count }), []);
  const setConflicts = useCallback((count: number) => dispatch({ type: 'conflicts', count }), []);

  const value = useMemo<ConnectionApi>(
    () => ({
      state,
      model,
      restricted: isServerRestricted(model),
      availability: (a) => actionAvailability(model, a),
      serverStateLoaded,
      reportRequest,
      setPending,
      setConflicts,
    }),
    [state, model, serverStateLoaded, reportRequest, setPending, setConflicts],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useConnection(): ConnectionApi {
  return useContext(Context);
}

export interface ConnectionBannerProps {
  /** Zeit des letzten Datenstands für die Anzeige (z. B. „Stand 07:00“), falls bekannt. */
  standText?: string | undefined;
  testId?: string;
}

/**
 * Sichtbarer Netzwerkzustand: Symbol und Text (nie nur Farbe), als Statusbereich für Screenreader. Im Zustand `online` bleibt der Bereich leer und ohne Rolle (die Ansage des Wechsels läuft zusätzlich über die Live-Region des Anbieters),
 * sobald er sichtbar ist, trägt er die Rolle Status. Zeigt bei ausstehenden Ereignissen „wird übertragen“.
 */
export function ConnectionBanner({
  standText,
  testId = 'connection-status',
}: ConnectionBannerProps) {
  const { state, model } = useConnection();
  const t = stateTexts[state];
  const visible = state !== 'online';
  return (
    <div
      role={visible ? 'status' : undefined}
      aria-live="polite"
      data-testid={testId}
      data-state={state}
      className="tk-connection"
      data-visible={visible}
    >
      {visible ? (
        <p>
          <span aria-hidden="true">{t.icon} </span>
          <strong>{t.label}.</strong> {t.detail}
          {model.pending > 0 ? <> {model.pending} Ereignis(se) wird/werden übertragen.</> : null}
          {standText ? <> {standText}.</> : null}
        </p>
      ) : null}
    </div>
  );
}
