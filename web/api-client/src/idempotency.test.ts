import { describe, expect, it } from 'vitest';
import { ApiError, CommandKeys, createApiClient, outcomeUnknown, runCommand } from './index';

function setup(responses: (Response | Error)[]) {
  const seen: (string | null)[] = [];
  let n = 0;
  const fetchFn = ((_url: string, init?: RequestInit) => {
    seen.push(new Headers(init?.headers).get('idempotency-key'));
    const r = responses[n++];
    if (r instanceof Error) return Promise.reject(r);
    return Promise.resolve(r as Response);
  }) as unknown as typeof fetch;
  let k = 0;
  const keys = new CommandKeys(() => `key-${++k}`);
  const client = createApiClient({ baseUrl: '', fetch: fetchFn, newKey: () => 'client-generated' });
  return { seen, keys, client };
}
const problem = (status: number, code: string) =>
  new Response(JSON.stringify({ status, code, title: 'x' }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
const ok = () => new Response(JSON.stringify({}), { status: 201 });
const body = {} as never;

describe('Idempotenzschlüssel (WP-M2-35 AK 4)', () => {
  it('Wiederholung nach Verbindungsabbruch trägt denselben Schlüssel, danach ein neuer', async () => {
    const { seen, keys, client } = setup([new TypeError('network'), ok(), ok()]);
    await expect(
      runCommand(client, keys, 'confirmBooking:o1', 'confirmBooking', { body }),
    ).rejects.toMatchObject({ status: 0 });
    await runCommand(client, keys, 'confirmBooking:o1', 'confirmBooking', { body });
    expect(seen).toEqual(['key-1', 'key-1']);
    await runCommand(client, keys, 'confirmBooking:o1', 'confirmBooking', { body });
    expect(seen[2]).toBe('key-2');
  });

  it('503 und „Vorgang läuft noch“ behalten den Schlüssel, endgültige Ablehnungen verbrauchen ihn', async () => {
    const { seen, keys, client } = setup([
      problem(503, 'SERVICE_UNAVAILABLE'),
      problem(409, 'OPERATION_IN_PROGRESS'),
      problem(409, 'OFFER_EXPIRED'),
      ok(),
    ]);
    const run = () => runCommand(client, keys, 'a', 'confirmBooking', { body }).catch(() => 0);
    await run();
    await run();
    await run();
    await run();
    expect(seen).toEqual(['key-1', 'key-1', 'key-1', 'key-2']);
  });

  it('verschiedene Absichten haben verschiedene Schlüssel', () => {
    const keys = new CommandKeys(() => crypto.randomUUID());
    expect(keys.keyFor('a')).not.toBe(keys.keyFor('b'));
    expect(keys.keyFor('a')).toBe(keys.keyFor('a'));
  });

  it('outcomeUnknown unterscheidet Ausgang unbekannt von Ablehnung', () => {
    expect(outcomeUnknown(new ApiError(0))).toBe(true);
    expect(outcomeUnknown(new ApiError(429))).toBe(false);
    expect(outcomeUnknown(new ApiError(422))).toBe(false);
    expect(outcomeUnknown(new Error('x'))).toBe(false);
  });
});
