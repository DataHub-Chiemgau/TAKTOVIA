import { ApiError, useApi } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { StatusBadge, useAnnounce } from '@taktovia/design-system';
import { useEffect, useRef, useState } from 'react';
import { createSseParser } from './sse';

/** Nachricht des Kanals (Schema `LiveEvent`): nur Referenzen und Statusfelder, nie Personendaten. */
export interface LiveMessage {
  event_id: string;
  cursor: string;
  type: string;
  topic: string;
  aggregate_id: string;
  aggregate_revision: number;
  service_id: string | null;
  shift_id: string | null;
  status: Record<string, unknown>;
  occurred_at: string;
}

/** Zustand des Kanals: `live` = Strom steht, `reconnecting` = Wiederaufbau, `polling` = Rückfall auf Abruf, `stale` = seit längerem kein Lebenszeichen, `ended` = Sitzung/Berechtigung beendet. */
export type LiveState = 'connecting' | 'live' | 'reconnecting' | 'polling' | 'stale' | 'ended';

export interface LiveChannelOptions {
  /** Leitstelle oder Fahrer; Standard Leitstelle. */
  channel?: 'operator' | 'driver';
  /** Nachricht eingetroffen (Ereignis tritt nur als Hinweis auf: der Zustand kommt über die gewöhnlichen Listen). */
  onMessage: (m: LiveMessage) => void;
  /**
   * Der Zustand der Ansicht ist neu zu laden: nach **jedem** (Wieder-)Verbindungsaufbau und beim Rückfall auf Abruf. Erst danach gelten die Ereignisse; so bleibt keine Anzeige dauerhaft falsch,
   * auch wenn zwischenzeitlich Ereignisse fehlten (Revisionsprüfung je Aggregat bleibt Sache der Ansicht).
   */
  onResync: () => void;
  /** Ohne Lebenszeichen (Nachricht, Herzschlag, Ende) länger als dies gilt die Ansicht als veraltet (Standard 40 s). */
  staleAfterMs?: number;
  /** Wartezeit vor dem Wiederaufbau (Standard 2 s, verdoppelt bis 30 s). */
  retryBaseMs?: number;
  /** Nach so vielen Fehlschlägen in Folge Rückfall auf Abruf (Standard 3; OP-05). */
  pollAfterFailures?: number;
  pollIntervalMs?: number;
  /** Test-Hook. */
  now?: () => number;
}

export interface LiveChannelStatus {
  state: LiveState;
  /** Zeitpunkt des letzten Lebenszeichens des Kanals bzw. des letzten erfolgreichen Abrufs (RFC 3339); `undefined` bis dahin. */
  lastSeen: string | undefined;
  stale: boolean;
}

const retryDelay = (base: number, failures: number): number =>
  Math.min(30_000, base * 2 ** Math.max(0, failures - 1));

/**
 * Gemeinsamer Client-Baustein des Live-Kanals (WP-M3-35, OP-05): liest den Strom über `fetch` (Token im Header), setzt mit der letzten `id` fort, lädt den Zustand nach jedem Aufbau
 * nach, fällt nach wiederholten Fehlern auf den Abruf zurück und kennzeichnet die Ansicht als veraltet, wenn kein Lebenszeichen mehr eintrifft. Nach Sitzungssperre (401) und Entzug
 * (403 oder `end` mit Grund) endet der Kanal, es gibt keinen stillen Wiederaufbau.
 */
export function useLiveChannel(options: LiveChannelOptions): LiveChannelStatus {
  const api = useApi();
  const announce = useAnnounce();
  const [status, setStatus] = useState<LiveChannelStatus>({
    state: 'connecting',
    lastSeen: undefined,
    stale: false,
  });
  const opts = useRef(options);
  opts.current = options;

  useEffect(() => {
    const abort = new AbortController();
    let lastSignal = (opts.current.now ?? Date.now)();
    let state: LiveState = 'connecting';
    let lastSeen: string | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const publish = (next: LiveState): void => {
      const before = state;
      state = next;
      const stale = next === 'stale' || next === 'reconnecting';
      setStatus({ state: next, lastSeen, stale: stale || next === 'ended' });
      // zurückhaltend: nur Zustandswechsel ansagen, nie einzelne Ereignisse
      if (
        before !== next &&
        (next === 'stale' || next === 'ended' || (next === 'live' && before !== 'connecting'))
      )
        announce(
          next === 'live'
            ? 'Live-Aktualisierung wieder aktiv.'
            : next === 'ended'
              ? 'Live-Aktualisierung beendet.'
              : 'Die Anzeige ist möglicherweise veraltet.',
        );
    };
    const signal = (): void => {
      lastSignal = (opts.current.now ?? Date.now)();
      lastSeen = new Date(lastSignal).toISOString();
    };
    const watchdog = setInterval(() => {
      const o = opts.current;
      if (state === 'live' && (o.now ?? Date.now)() - lastSignal > (o.staleAfterMs ?? 40_000))
        publish('stale');
    }, 1000);

    let cursor: string | undefined;
    let failures = 0;
    const op =
      opts.current.channel === 'driver' ? 'streamDriverLiveEvents' : 'streamOperatorLiveEvents';

    const wait = (ms: number): Promise<void> =>
      new Promise((resolve) => {
        timer = setTimeout(resolve, ms);
      });

    async function pollOnce(client: ApiClient): Promise<void> {
      if (opts.current.channel === 'driver') {
        // Fahrer: der Abruf ist die bedingte Run-Abfrage (ETag) bzw. der Änderungsstrom der Planrevisionen; der Baustein stößt sie über den Nachladehaken an
        signal();
        opts.current.onResync();
        return;
      }
      const page = await client.call('listOperatorLiveEvents', {
        query: cursor ? { after: cursor } : {},
        signal: abort.signal,
      });
      signal();
      if (!cursor) opts.current.onResync();
      for (const item of page.items) opts.current.onMessage(item as unknown as LiveMessage);
      cursor = page.next_cursor;
    }

    async function connect(client: ApiClient): Promise<'ended' | 'again'> {
      if (!client.openStream) throw new ApiError(0);
      const response = await client.openStream(op, {
        query: { ...(cursor ? { after: cursor } : {}), wait_seconds: 25 },
        signal: abort.signal,
      });
      if (!response.body) throw new ApiError(0);
      failures = 0;
      signal();
      publish('live');
      opts.current.onResync(); // Zustand neu laden, bevor Ereignisse gelten
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parse = createSseParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) return 'again';
        for (const f of parse(decoder.decode(value, { stream: true }))) {
          signal();
          if (state === 'stale') publish('live');
          if (f.event === 'ready' && f.data && !cursor)
            cursor = (JSON.parse(f.data) as { cursor: string }).cursor;
          else if (f.event === 'live' && f.data) {
            const m = JSON.parse(f.data) as LiveMessage;
            cursor = m.cursor;
            opts.current.onMessage(m);
          } else if (f.event === 'end' && f.data) {
            const end = JSON.parse(f.data) as { reason: string; cursor: string };
            cursor = end.cursor;
            return end.reason === 'max_duration' ? 'again' : 'ended';
          }
        }
      }
    }

    void (async () => {
      const client = api;
      for (;;) {
        if (abort.signal.aborted) return;
        try {
          if (failures >= (opts.current.pollAfterFailures ?? 3)) {
            publish('polling');
            await pollOnce(client);
            await wait(opts.current.pollIntervalMs ?? 5000);
            continue;
          }
          const result = await connect(client);
          if (result === 'ended') {
            publish('ended');
            return;
          }
        } catch (e) {
          if (abort.signal.aborted) return;
          if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
            publish('ended');
            return;
          }
          failures += 1;
          if (failures < (opts.current.pollAfterFailures ?? 3)) publish('reconnecting');
          await wait(retryDelay(opts.current.retryBaseMs ?? 2000, failures));
        }
      }
    })();

    return () => {
      abort.abort();
      clearInterval(watchdog);
      if (timer) clearTimeout(timer);
    };
  }, [api, announce]);

  return status;
}

/** Datenstand und Veraltet-Kennzeichnung der Ansicht (Kap. 06 §6.1): Text und Symbol, nie nur Farbe. */
export function LiveStatusBadge({ status }: { status: LiveChannelStatus }) {
  const tone =
    status.state === 'live'
      ? 'ok'
      : status.state === 'polling'
        ? 'info'
        : status.state === 'connecting'
          ? 'neutral'
          : 'warning';
  const label: Record<LiveState, string> = {
    connecting: 'Verbindung wird aufgebaut',
    live: 'Live',
    reconnecting: 'Verbindung unterbrochen, veraltet',
    polling: 'Aktualisierung per Abruf',
    stale: 'Veraltet: kein Lebenszeichen',
    ended: 'Beendet: bitte neu laden oder anmelden',
  };
  return (
    <span data-testid="live-status" data-state={status.state}>
      <StatusBadge tone={tone}>{label[status.state]}</StatusBadge>
      {status.lastSeen ? (
        <span>
          {' '}
          · Datenstand{' '}
          <time dateTime={status.lastSeen}>
            {new Date(status.lastSeen).toLocaleTimeString('de-DE')}
          </time>
        </span>
      ) : null}
    </span>
  );
}
