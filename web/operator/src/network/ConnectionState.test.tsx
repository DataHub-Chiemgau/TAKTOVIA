import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { ConnectionProvider, LiveRegionProvider } from '@taktovia/design-system';
import { LiveStatusBadge, useLiveChannel } from '../live/LiveChannel';
import { NetzBanner } from './NetzAnbieter';

const enc = new TextEncoder();

function controlled() {
  let ctl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      ctl = c;
    },
  });
  return {
    response: new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
    push: (s: string) => ctl.enqueue(enc.encode(s)),
    fail: () => ctl.error(new Error('abgebrochen')),
  };
}

function Ansicht({ now }: { now: () => number }) {
  const status = useLiveChannel({
    onMessage: () => undefined,
    onResync: () => undefined,
    retryBaseMs: 1,
    pollAfterFailures: 50,
    now,
  });
  return (
    <>
      <NetzBanner />
      <LiveStatusBadge status={status} />
    </>
  );
}

describe('Netzwerkzustand Operator-Livekanal (WP-M5-09, UI-07)', () => {
  afterEach(cleanup);

  it('livekanal_verlust_sichtbar_mit_datenstand', async () => {
    const first = controlled();
    const second = controlled();
    let opened = 0;
    let broken = false;
    const client = {
      call: vi.fn((id: string) =>
        id === 'getMe'
          ? Promise.resolve(
              sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
            )
          : Promise.reject(new ApiError(404)),
      ),
      openStream: vi.fn(() => {
        opened += 1;
        if (opened === 1) return Promise.resolve(first.response);
        if (broken) return Promise.reject(new ApiError(0));
        return Promise.resolve(second.response);
      }),
    } as unknown as ApiClient;
    let t = Date.parse('2026-12-01T06:00:00Z');
    render(
      <LiveRegionProvider>
        <SessionProvider client={client}>
          <ConnectionProvider>
            <Ansicht now={() => t} />
          </ConnectionProvider>
        </SessionProvider>
      </LiveRegionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'));
    await waitFor(() =>
      expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'online'),
    );
    await act(async () => {
      first.push(`event: ready\ndata: {"cursor":"5.1","as_of":"2026-12-01T06:00:00Z"}\n\n`);
    });
    // Verbindungsverlust des Livekanals
    broken = true;
    t = Date.parse('2026-12-01T06:05:00Z');
    await act(async () => {
      first.fail();
    });
    await waitFor(() =>
      expect(screen.getByTestId('live-status').dataset.state).toBe('reconnecting'),
    );
    const live = screen.getByTestId('live-status');
    expect(live).toHaveTextContent('Verbindung unterbrochen, veraltet'); // Text, nicht nur Farbe
    expect(live).toHaveTextContent('Datenstand'); // letzter Datenstand sichtbar
    expect(live).not.toHaveTextContent(/^Live/);
    await waitFor(() =>
      expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'limited'),
    );
    expect(screen.getByTestId('connection-status')).toHaveTextContent('Eingeschränkt');
    expect(screen.getByTestId('connection-status')).toHaveAttribute('role', 'status');
    // Wiederaufbau: der Zustand endet erst mit dem laufenden Strom
    broken = false;
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).toBe('live'), {
      timeout: 5000,
    });
    await waitFor(() =>
      expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'online'),
    );
  });
});
