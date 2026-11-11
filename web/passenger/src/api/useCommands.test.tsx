import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { SessionProvider, createApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { useCommands } from './useCommands';

describe('Befehle der Passenger-PWA (WP-M2-35 AK 4)', () => {
  it('derselbe Befehl nach Verbindungsabbruch geht mit demselben Idempotency-Key, ein neuer Befehl mit neuem', async () => {
    const keys: (string | null)[] = [];
    let failNext = true;
    const client = createApiClient({
      baseUrl: '',
      fetch: ((url: string, init?: RequestInit) => {
        if (url.endsWith('/v1/me'))
          return Promise.resolve(new Response(JSON.stringify(sampleMe())));
        keys.push(new Headers(init?.headers).get('idempotency-key'));
        if (failNext) {
          failNext = false;
          return Promise.reject(new TypeError('Verbindung abgebrochen'));
        }
        return Promise.resolve(new Response('{}', { status: 201 }));
      }) as unknown as typeof fetch,
    });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <SessionProvider client={client}>{children}</SessionProvider>
    );
    const { result } = renderHook(() => useCommands(), { wrapper });
    const confirm = () =>
      result.current.run('confirmBooking:offer-1', 'confirmBooking', { body: {} as never });
    await act(async () => {
      await expect(confirm()).rejects.toMatchObject({ status: 0 });
    });
    expect(result.current.pending('confirmBooking:offer-1')).toBe(true);
    await act(async () => void (await confirm()));
    expect(result.current.pending('confirmBooking:offer-1')).toBe(false);
    await act(async () => void (await confirm()));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });
});
