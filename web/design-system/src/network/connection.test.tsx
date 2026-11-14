import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LiveRegionProvider } from '../components/LiveRegion';
import {
  actionAvailability,
  deriveConnectionState,
  initialConnection,
  reduceConnection,
  standHhmm,
} from './connection';
import type { ConnectionEvent, ConnectionModel } from './connection';
import { ConnectionBanner, ConnectionProvider, useConnection } from './ConnectionProvider';
import type { ConnectionSource } from './ConnectionProvider';

const run = (events: ConnectionEvent[], from: ConnectionModel = initialConnection) =>
  events.reduce(reduceConnection, from);

describe('Verbindungszustand (WP-M5-09): Zustandsautomat', () => {
  it('fuenf_zustaende_werden_abgeleitet', () => {
    expect(deriveConnectionState(initialConnection)).toBe('online');
    expect(deriveConnectionState(run([{ type: 'limited', value: true }]))).toBe('limited');
    expect(deriveConnectionState(run([{ type: 'link', up: false }]))).toBe('offline');
    expect(deriveConnectionState(run([{ type: 'pending', count: 2 }]))).toBe('syncing');
    expect(deriveConnectionState(run([{ type: 'conflicts', count: 1 }]))).toBe('conflict');
  });

  it('wiederverbindung_bleibt_syncing_bis_der_serverstand_geladen_ist', () => {
    const back = run([
      { type: 'link', up: false },
      { type: 'link', up: true },
    ]);
    expect(deriveConnectionState(back)).toBe('syncing');
    expect(actionAvailability(back, 'bind-booking').allowed).toBe(false);
    const loaded = reduceConnection(back, {
      type: 'server-state-loaded',
      at: '2026-11-21T07:00:00Z',
    });
    expect(deriveConnectionState(loaded)).toBe('online');
    expect(actionAvailability(loaded, 'bind-booking').allowed).toBe(true);
    expect(loaded.lastServerStateAt).toBe('2026-11-21T07:00:00Z');
  });

  it('serveraktionen_offline_gesperrt_mit_grund_und_naechstem_schritt', () => {
    const off = run([{ type: 'link', up: false }]);
    for (const a of [
      'bind-booking',
      'checkout',
      'cancel-booking',
      'release-vehicle',
      'lift-block',
    ] as const) {
      const v = actionAvailability(off, a);
      expect(v.allowed, a).toBe(false);
      expect(v.reason.length, a).toBeGreaterThan(10);
      expect(v.nextStep.length, a).toBeGreaterThan(10);
    }
    expect(actionAvailability(off, 'record-offline-draft').allowed).toBe(true);
    expect(actionAvailability(off, 'view-last-state').allowed).toBe(true);
  });

  it('stand_hhmm_in_der_zeitzone', () => {
    expect(standHhmm('2026-11-21T06:00:00Z', 'Europe/Berlin')).toBe('Stand 07:00');
  });
});

function fakeSource(initial = true) {
  let up = initial;
  const listeners = new Set<() => void>();
  const source: ConnectionSource = {
    isOnline: () => up,
    subscribe: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
  };
  return {
    source,
    set: (v: boolean) => {
      up = v;
      listeners.forEach((l) => l());
    },
  };
}

function Probe() {
  const c = useConnection();
  return (
    <>
      <ConnectionBanner />
      <p data-testid="restricted">{String(c.restricted)}</p>
    </>
  );
}

describe('Verbindungszustand (WP-M5-09): Anbieter', () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('kurzer_aussetzer_loest_weder_anzeige_noch_ansage_aus', async () => {
    vi.useFakeTimers();
    const net = fakeSource();
    render(
      <LiveRegionProvider>
        <ConnectionProvider source={net.source} offlineDelayMs={500}>
          <Probe />
        </ConnectionProvider>
      </LiveRegionProvider>,
    );
    for (let i = 0; i < 5; i += 1) {
      act(() => net.set(false));
      act(() => void vi.advanceTimersByTime(200));
      act(() => net.set(true));
      act(() => void vi.advanceTimersByTime(200));
    }
    expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'online');
    expect(
      screen
        .getAllByRole('status')
        .map((e) => e.textContent)
        .join(''),
    ).toBe('');
  });

  it('wechsel_wird_angesagt_und_offline_endet_erst_nach_geladenem_serverstand', async () => {
    vi.useFakeTimers();
    const net = fakeSource();
    let release!: () => void;
    const load = vi.fn(() => new Promise<void>((resolve) => (release = resolve)));
    render(
      <LiveRegionProvider>
        <ConnectionProvider source={net.source} offlineDelayMs={100} loadServerState={load}>
          <Probe />
        </ConnectionProvider>
      </LiveRegionProvider>,
    );
    act(() => net.set(false));
    await act(async () => void vi.advanceTimersByTime(150));
    expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'offline');
    expect(screen.getByTestId('connection-status')).toHaveTextContent('Offline');
    expect(screen.getByTestId('restricted')).toHaveTextContent('true');
    // Ansage über die Live-Region (zweiter Statusbereich neben dem Banner)
    expect(screen.getAllByRole('status').some((e) => /Offline\./.test(e.textContent ?? ''))).toBe(
      true,
    );
    act(() => net.set(true));
    await act(async () => void vi.advanceTimersByTime(10));
    expect(load).toHaveBeenCalled();
    expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'syncing');
    expect(screen.getByTestId('restricted')).toHaveTextContent('true');
    await act(async () => {
      release();
      await Promise.resolve();
    });
    expect(screen.getByTestId('connection-status')).toHaveAttribute('data-state', 'online');
    expect(screen.getByTestId('restricted')).toHaveTextContent('false');
  });
});
