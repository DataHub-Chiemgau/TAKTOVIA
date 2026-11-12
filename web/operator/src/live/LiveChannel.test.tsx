import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { LiveRegionProvider } from '@taktovia/design-system';
import { LiveStatusBadge, useLiveChannel } from './LiveChannel';
import type { LiveMessage } from './LiveChannel';
import { createSseParser } from './sse';

const enc = new TextEncoder();
const event = (cursor: string, type = 'alert.raised'): string =>
  `id: ${cursor}\nevent: live\ndata: ${JSON.stringify({ event_id: `e-${cursor}`, cursor, type, topic: 'alert', aggregate_id: 'a', aggregate_revision: 1, service_id: null, shift_id: null, status: {}, occurred_at: '2026-12-01T06:00:00Z' })}\n\n`;

/** Strom, den der Test von außen füllt und beendet (Modell des Servers; der echte Strom wird in `LiveChannelHttpTest` gegen die Anwendung geprüft). */
function controlled(): {
  response: Response;
  push: (s: string) => void;
  close: () => void;
  fail: () => void;
} {
  let ctl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      ctl = c;
    },
  });
  return {
    response: new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
    push: (s) => ctl.enqueue(enc.encode(s)),
    close: () => ctl.close(),
    fail: () => ctl.error(new Error('abgebrochen')),
  };
}

type Opened = { query: Record<string, unknown> };

function Probe(props: {
  client: ApiClient;
  onMessage: (m: LiveMessage) => void;
  onResync: () => void;
  staleAfterMs?: number;
  now?: () => number;
  retryBaseMs?: number;
  pollAfterFailures?: number;
  channel?: 'operator' | 'driver';
}) {
  const status = useLiveChannel({
    onMessage: props.onMessage,
    onResync: props.onResync,
    retryBaseMs: props.retryBaseMs ?? 1,
    pollIntervalMs: 5,
    ...(props.staleAfterMs !== undefined ? { staleAfterMs: props.staleAfterMs } : {}),
    ...(props.now ? { now: props.now } : {}),
    ...(props.pollAfterFailures !== undefined
      ? { pollAfterFailures: props.pollAfterFailures }
      : {}),
    ...(props.channel ? { channel: props.channel } : {}),
  });
  return <LiveStatusBadge status={status} />;
}

function mount(client: ApiClient, extra: Partial<Parameters<typeof Probe>[0]> = {}) {
  const messages: LiveMessage[] = [];
  const onResync = vi.fn();
  render(
    <LiveRegionProvider>
      <SessionProvider client={{ ...client, call: client.call } as ApiClient}>
        <Probe client={client} onMessage={(m) => messages.push(m)} onResync={onResync} {...extra} />
      </SessionProvider>
    </LiveRegionProvider>,
  );
  return { messages, onResync };
}

const base = (over: Partial<ApiClient>): ApiClient =>
  ({
    call: vi.fn((id: string) =>
      id === 'getMe'
        ? Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }))
        : Promise.reject(new ApiError(404)),
    ),
    ...over,
  }) as unknown as ApiClient;

describe('Live-Kanal (Client-Baustein)', () => {
  it('Zustandsänderung erscheint ohne Neuladen und der Zustand wird nach dem Aufbau nachgeladen', async () => {
    const s = controlled();
    const opened: Opened[] = [];
    const client = base({
      openStream: vi.fn((_id: string, args: Opened) => {
        opened.push(args);
        return Promise.resolve(s.response);
      }) as never,
    });
    const { messages, onResync } = mount(client);
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
    expect(onResync).toHaveBeenCalledTimes(1);
    await act(async () => {
      s.push(`event: ready\ndata: {"cursor":"5.1","as_of":"2026-12-01T06:00:00Z"}\n\n`);
      s.push(event('5.2'));
    });
    await waitFor(() => expect(messages.map((m) => m.cursor)).toEqual(['5.2']));
    expect(screen.getByTestId('live-status').textContent).toContain('Datenstand');
  });

  it('nach Kanalabbruch wird mit der letzten id fortgesetzt und der Zustand neu geladen', async () => {
    const first = controlled();
    const second = controlled();
    const opened: Opened[] = [];
    const streams = [first, second];
    const client = base({
      openStream: vi.fn((_id: string, args: Opened) => {
        opened.push(args);
        return Promise.resolve((streams[opened.length - 1] ?? second).response);
      }) as never,
    });
    const { messages, onResync } = mount(client);
    await waitFor(() => expect(opened).toHaveLength(1));
    await act(async () => {
      first.push(`event: ready\ndata: {"cursor":"5.1","as_of":"x"}\n\n`);
      first.push(event('5.2'));
    });
    await waitFor(() => expect(messages).toHaveLength(1));
    await act(async () => {
      first.fail();
    });
    await waitFor(() => expect(opened).toHaveLength(2));
    expect(opened[1]?.query.after).toBe('5.2');
    await waitFor(() => expect(onResync).toHaveBeenCalledTimes(2));
    await act(async () => {
      second.push(event('5.3'));
    });
    await waitFor(() => expect(messages.map((m) => m.cursor)).toEqual(['5.2', '5.3']));
  });

  it('kennzeichnet die Ansicht als veraltet, wenn kein Lebenszeichen mehr eintrifft, und hebt es mit dem nächsten auf', async () => {
    const s = controlled();
    let t = 1_000_000;
    const client = base({ openStream: vi.fn(() => Promise.resolve(s.response)) as never });
    mount(client, { staleAfterMs: 2000, now: () => t });
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
    t += 5000;
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('stale'), {
      timeout: 3000,
    });
    expect(screen.getByTestId('live-status').textContent).toContain('Veraltet');
    await act(async () => {
      s.push(': heartbeat\n\n');
    });
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
  });

  it('fällt nach wiederholten Fehlern auf den Abruf zurück', async () => {
    const calls: string[] = [];
    const client = base({
      openStream: vi.fn(() => Promise.reject(new ApiError(503))) as never,
      call: vi.fn((id: string) => {
        calls.push(id);
        if (id === 'getMe')
          return Promise.resolve(
            sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
          );
        return Promise.resolve({
          items: [
            {
              event_id: 'e',
              cursor: '9.1',
              type: 'alert.raised',
              topic: 'alert',
              aggregate_id: 'a',
              aggregate_revision: 1,
              service_id: null,
              shift_id: null,
              status: {},
              occurred_at: 'x',
            },
          ],
          next_cursor: '9.1',
          has_more: false,
          as_of: 'x',
        });
      }) as never,
    });
    const { messages } = mount(client, { pollAfterFailures: 2 });
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('polling'));
    await waitFor(() => expect(calls).toContain('listOperatorLiveEvents'));
    await waitFor(() => expect(messages.length).toBeGreaterThan(0));
  });

  it('beendet den Kanal nach gesperrter Sitzung (401) und Entzug (403) ohne stillen Wiederaufbau', async () => {
    for (const status of [401, 403]) {
      const open = vi.fn(() => Promise.reject(new ApiError(status)));
      const client = base({ openStream: open as never });
      const { unmount } = render(
        <LiveRegionProvider>
          <SessionProvider client={client}>
            <Probe client={client} onMessage={() => undefined} onResync={() => undefined} />
          </SessionProvider>
        </LiveRegionProvider>,
      );
      await waitFor(() =>
        expect(screen.getAllByTestId('live-status').at(-1)?.dataset.state).toBe('ended'),
      );
      await new Promise((r) => setTimeout(r, 30));
      expect(open).toHaveBeenCalledTimes(1);
      unmount();
    }
  });

  it('beendet den Kanal, wenn der Server den Strom mit Grund beendet', async () => {
    const s = controlled();
    const open = vi.fn(() => Promise.resolve(s.response));
    mount(base({ openStream: open as never }));
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
    await act(async () => {
      s.push('event: end\ndata: {"reason":"session_revoked","cursor":"5.9"}\n\n');
    });
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('ended'));
    expect(open).toHaveBeenCalledTimes(1);
  });
});

describe('SSE-Parser', () => {
  it('setzt zerteilte Stücke zusammen und trennt Herzschlag von Ereignissen', () => {
    const parse = createSseParser();
    expect(parse('id: 1.2\nevent: li')).toEqual([]);
    const frames = parse('ve\ndata: {"a":1}\n\n: heartbeat\n\n');
    expect(frames).toEqual([
      { event: 'live', id: '1.2', data: '{"a":1}', heartbeat: false },
      { event: 'message', heartbeat: true },
    ]);
  });
});
