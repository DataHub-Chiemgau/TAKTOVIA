import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { Registrierung } from './Registrierung';

/** Ersatz des Servers (kein SMS-/E-Mail-Versand, kein Keycloak): prüft nur den Ablauf der Oberfläche. Serververhalten: PassengerApiTest im Backend. */
function client(
  register: (b: unknown) => unknown,
  confirm: (b: { code: string }) => unknown,
): ApiClient {
  return {
    call(id: string, args: { body?: unknown }) {
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['PASSENGER'] }));
      const handler =
        id === 'registerPassenger'
          ? register
          : id === 'confirmContactVerification'
            ? confirm
            : undefined;
      if (!handler) return Promise.reject(new ApiError(404));
      const r = handler(args.body as never);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    },
  } as unknown as ApiClient;
}

describe('Registrierung (UI-05 Basis, WP-M1-28)', () => {
  afterEach(cleanup);

  it('Registrierung per E-Mail und Telefon, dann Kontakt verifizieren', async () => {
    const calls: unknown[] = [];
    const done = vi.fn();
    const c = client(
      (b) => (calls.push(b), { id: 'x' }),
      (b) => (b.code === '123456' ? { status: 'VERIFIED' } : new ApiError(422)),
    );
    render(
      <SessionProvider client={c}>
        <Registrierung onDone={done} />
      </SessionProvider>,
    );
    fireEvent.click(screen.getByRole('radio', { name: 'Telefon' }));
    fireEvent.change(screen.getByLabelText('Telefonnummer (mit Landesvorwahl)'), {
      target: { value: '+49 151 2345678' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Registrieren' }));
    await screen.findByRole('heading', { name: 'Kontakt verifizieren' });
    expect(screen.getByRole('status')).toHaveTextContent('Verifikation fehlt');
    expect(calls).toEqual([
      { contact: { channel: 'PHONE', value: '+49 151 2345678' }, locale: 'de-DE' },
    ]);
    fireEvent.change(screen.getByLabelText('Bestätigungscode'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Kontakt bestätigen' }));
    await screen.findByText('Der Code ist falsch oder abgelaufen.');
    expect(done).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Bestätigungscode'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Kontakt bestätigen' }));
    await vi.waitFor(() => expect(done).toHaveBeenCalled());
  });

  it('ungültiger Kontakt (422) erscheint am Feld, ohne den Wert zu wiederholen; Doppelregistrierung (409) bleibt allgemein', async () => {
    let n = 0;
    const c = client(
      () =>
        n++ === 0
          ? new ApiError(422, {
              type: 't',
              title: 'x',
              status: 422,
              code: 'VALIDATION_FAILED',
              correlation_id: 'c',
              violations: [{ pointer: '/contact/value', code: 'INVALID_FORMAT', message: 'm' }],
            })
          : new ApiError(409),
      () => ({}),
    );
    render(
      <SessionProvider client={c}>
        <Registrierung onDone={() => undefined} />
      </SessionProvider>,
    );
    fireEvent.change(screen.getByLabelText('E-Mail-Adresse'), {
      target: { value: 'kein-kontakt' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Registrieren' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Das Format ist ungültig.');
    fireEvent.click(screen.getByRole('button', { name: 'Registrieren' }));
    await screen.findByText('Die Registrierung ist mit diesen Angaben nicht möglich.');
  });
});
