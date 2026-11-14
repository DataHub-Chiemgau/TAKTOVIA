import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, OperatorBookingRow } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { App } from '../App';

const PASSENGER = '00000000-0000-4000-8000-0000000000aa';
const SERVICE = '00000000-0000-4000-8000-00000000aaaa';

const row: OperatorBookingRow = {
  id: '00000000-0000-4000-8000-000000000001',
  booking_number: 'BK-2026-000001',
  service_id: SERVICE,
  state: 'CONFIRMED',
  revision: 3,
  created_at: '2026-12-01T06:00:00Z',
  pickup_window: { earliest_at: '2026-12-01T09:00:00Z', latest_at: '2026-12-01T09:15:00Z' },
  arrival_window: { earliest_at: '2026-12-01T09:20:00Z', latest_at: '2026-12-01T09:45:00Z' },
  passengers: 2,
  wheelchair_spaces: 0,
  vehicle_id: null,
  vehicle_label: null,
  passenger_ref: PASSENGER,
  flags: [],
};

const offer = {
  id: '00000000-0000-4000-8000-0000000000f1',
  revision: 1,
  gross: { amount_minor: 950, currency: 'EUR', exponent: 2 },
  pickup_window: { earliest_at: '2026-12-01T09:00:00Z', latest_at: '2026-12-01T09:15:00Z' },
};

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }),
        );
      if (id === 'searchOperatorBookings')
        return Promise.resolve({ items: [row], next_cursor: null, as_of: '2026-12-01T08:05:00Z' });
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  render(
    <MemoryRouter initialEntries={['/leitstelle/auftraege']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { calls };
}

const pick = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('UI-07 Eigene Buchung bearbeiten (WP-M3-39, LH-6.7-OPS-004)', () => {
  it('stornieren_verlangt_grund_und_zeigt_folgen_412_ueberschreibt_nie', async () => {
    let fail: unknown;
    const { calls } = setup((id) => {
      if (id === 'cancelBooking') {
        if (fail) throw fail;
        return { ...row, state: 'CANCELED' };
      }
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'BK-2026-000001 stornieren' }));
    const panel = screen.getByRole('region', { name: /stornieren/ });
    expect(within(panel).getByText(/Die Reservierung wird freigegeben/)).toBeTruthy();
    // ohne Grund ist die Aktion nicht auslösbar
    const button = within(panel).getByRole('button', { name: 'Stornieren' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    // veralteter Stand: 412 mit Hinweis, nichts wird überschrieben
    pick('Grund (Pflicht)', 'OPERATOR_CANCELLATION');
    fail = new ApiError(412);
    fireEvent.click(button);
    await screen.findByText(/zwischenzeitlich geändert.*nichts überschrieben/);
    expect(screen.getByRole('region', { name: /stornieren/ })).toBeTruthy();
    // danach gelingt es mit dem Grund; Revision der Zeile geht als If-Match mit
    fail = undefined;
    fireEvent.change(screen.getByLabelText('Anmerkung'), {
      target: { value: 'Fahrgast hat abgesagt' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Stornieren' }));
    await screen.findByText(/wurde storniert/);
    const sent = calls.filter((c) => c.id === 'cancelBooking').at(-1)?.args;
    expect(sent).toMatchObject({
      path: { booking_id: row.id },
      revision: 3,
      body: { reason: 'OPERATOR_CANCELLATION', reason_text: 'Fahrgast hat abgesagt' },
    });
  });

  it('anlegen_fuer_fahrgast_geht_ueber_angebot_hold_bestaetigung_mit_grund', async () => {
    const { calls } = setup((id) => {
      if (id === 'createRideOffer') return { attempt_id: 'a', offers: [offer], rejection: null };
      if (id === 'holdRideOffer')
        return { id: 'h1', state: 'HELD', expires_at: '2026-12-01T08:10:00Z' };
      if (id === 'getRideOffer') return { ...offer, revision: 2 };
      if (id === 'confirmBooking') return { ...row, booking_number: 'BK-2026-000002' };
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Buchung für Fahrgast anlegen' }));
    pick('Fahrgast der Buchung (Verweis)', PASSENGER);
    pick('Dienst', SERVICE);
    pick('Abfahrtswunsch', '2026-12-01T10:00');
    fireEvent.click(screen.getByRole('button', { name: 'Angebot einholen' }));
    const region = await screen.findByRole('region', { name: 'Angebot' });
    expect(within(region).getByText(/9,50 EUR/)).toBeTruthy();
    const commit = within(region).getByRole('button', {
      name: 'Reservieren und Buchung anlegen',
    }) as HTMLButtonElement;
    expect(commit.disabled).toBe(true); // Grund fehlt
    pick('Grund (Pflicht)', 'PHONE_REQUEST');
    fireEvent.click(commit);
    await screen.findByText(/BK-2026-000002 wurde für den Fahrgast angelegt/);
    expect(calls.find((c) => c.id === 'createRideOffer')?.args).toMatchObject({
      body: { booking_subject: { kind: 'DELEGATED', passenger_id: PASSENGER }, passengers: 1 },
    });
    expect(calls.find((c) => c.id === 'confirmBooking')?.args).toMatchObject({
      revision: 2,
      body: { hold_id: 'h1', reason_code: 'PHONE_REQUEST' },
    });
  });

  it('aendern_fragt_zustimmung_erst_wenn_der_server_sie_verlangt_und_zeigt_konflikt', async () => {
    let consented = false;
    const { calls } = setup((id, args) => {
      if (id === 'createRideOffer') return { attempt_id: 'a', offers: [offer], rejection: null };
      if (id === 'holdRideOffer')
        return { id: 'h2', state: 'HELD', expires_at: '2026-12-01T08:10:00Z' };
      if (id === 'getRideOffer') return { ...offer, revision: 2 };
      if (id === 'changeBooking') {
        const body = args.body as { passenger_consent?: unknown };
        if (!body.passenger_consent) throw new ApiError(422);
        consented = true;
        return {
          booking: { ...row, booking_number: 'BK-2026-000009' },
          replaced: row,
          change_id: 'c',
          replayed: false,
          price_changed: true,
          time_changed: false,
        };
      }
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'BK-2026-000001 ändern' }));
    const panel = screen.getByRole('region', { name: /ändern/ });
    expect(within(panel).getByText(/neue Buchung mit neuer Buchungsnummer/)).toBeTruthy();
    expect(
      (screen.getByLabelText('Fahrgast der Buchung (Verweis)') as HTMLInputElement).readOnly,
    ).toBe(true);
    pick('Abfahrtswunsch', '2026-12-01T10:00');
    fireEvent.click(screen.getByRole('button', { name: 'Angebot einholen' }));
    await screen.findByRole('region', { name: 'Angebot' });
    pick('Grund (Pflicht)', 'PASSENGER_REQUEST');
    fireEvent.click(screen.getByRole('button', { name: 'Reservieren und Buchung ändern' }));
    await screen.findByText(/Preis oder Abholzeit ändern sich/);
    const send = screen.getByRole('button', {
      name: 'Reservieren und Buchung ändern',
    }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    pick('Zustimmung des Fahrgasts eingeholt per', 'PHONE');
    fireEvent.click(send);
    await waitFor(() => expect(consented).toBe(true));
    await screen.findByText(/durch BK-2026-000009 ersetzt.*Der Preis hat sich geändert/);
    expect(calls.filter((c) => c.id === 'changeBooking').at(-1)?.args).toMatchObject({
      path: { booking_id: row.id },
      revision: 3,
      body: { reason_code: 'PASSENGER_REQUEST', passenger_consent: { channel: 'PHONE' } },
    });
  });
});
