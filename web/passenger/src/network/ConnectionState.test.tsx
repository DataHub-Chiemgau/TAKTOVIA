import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { ConnectionProvider, LiveRegionProvider } from '@taktovia/design-system';
import type { ConnectionSource } from '@taktovia/design-system';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BookingDetail } from '../booking/BookingDetail';
import { CheckoutScreen } from '../booking/CheckoutScreen';
import { OFFER_ID, buchungsWelt } from '../booking/buchungsWelt';
import { reiseWelt, renderReise } from '../booking/reiseWelt';
import { Checkout } from '../booking/Checkout';
import { NetzBanner } from './NetzAnbieter';

function netz(initial = true) {
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

const zustand = () => screen.getByTestId('connection-status');

describe('Netzwerkzustand Passenger-PWA (WP-M5-09, NFR-009)', () => {
  afterEach(cleanup);

  it('offline_zeigt_letzten_buchungsstatus_mit_stand_hhmm', async () => {
    const { client, world } = reiseWelt();
    const net = netz();
    const { container } = renderReise(client, (id) => (
      <ConnectionProvider source={net.source} offlineDelayMs={1} retryMs={5}>
        <NetzBanner />
        <BookingDetail bookingId={id} zone="Europe/Berlin" pollMs={10} />
      </ConnectionProvider>
    ));
    await screen.findByTestId('eta');
    expect(screen.queryByTestId('eta-stand')).toBeNull();
    // Verbindungsverlust: Gerät offline und Anfragen scheitern
    world.down = true;
    act(() => net.set(false));
    await waitFor(() => expect(zustand()).toHaveAttribute('data-state', 'offline'));
    // letzter bekannter Status bleibt sichtbar, ETA und Fahrzeug als „Stand hh:mm“, keine Livekennzeichnung
    expect(screen.getByTestId('buchung-status')).toHaveTextContent('Bestätigt');
    await screen.findByTestId('eta-stand');
    expect(screen.getByTestId('eta-stand')).toHaveTextContent('Stand 07:00');
    expect(screen.getByTestId('eta')).toHaveTextContent('Stand 07:00');
    expect(screen.getByTestId('fahrzeug')).toHaveTextContent('Stand 07:00: Bus 12');
    expect(screen.getByTestId('letzter-stand')).toHaveTextContent('21.11.2026, 07:00 Uhr');
    expect(container.textContent ?? '').not.toMatch(/\blive\b/i);
    // Zustand als Text und Symbol im Statusbereich, nicht nur über Farbe
    expect(zustand()).toHaveAttribute('role', 'status');
    expect(zustand()).toHaveTextContent('Offline.');
    expect(zustand()).toHaveTextContent('✕');
    expect(formatViolations(await runAxe(container))).toBe('');
    // Rückkehr: erst „Synchronisation läuft“, nach geladenem Serverstand wieder online
    world.down = false;
    act(() => net.set(true));
    await waitFor(() => expect(zustand()).toHaveAttribute('data-state', 'online'));
    expect(screen.queryByTestId('eta-stand')).toBeNull();
  });

  it('checkout_offline_nicht_abschliessbar', async () => {
    const { client, world } = buchungsWelt();
    const net = netz(false);
    render(
      <MemoryRouter initialEntries={[`/checkout/${OFFER_ID}`]}>
        <SessionProvider client={client}>
          <LiveRegionProvider>
            <ConnectionProvider source={net.source}>
              <NetzBanner />
              <Routes>
                <Route path="/checkout/:offerId" element={<CheckoutScreen pollMs={5} />} />
              </Routes>
            </ConnectionProvider>
          </LiveRegionProvider>
        </SessionProvider>
      </MemoryRouter>,
    );
    const gesperrt = await screen.findByTestId('aktion-gesperrt');
    // Grund und nächster Schritt statt stiller Deaktivierung; keine Zahlungsaktion, kein vorgetäuschter Erfolg
    expect(gesperrt).toHaveTextContent('nicht abgeschlossen werden');
    expect(gesperrt).toHaveTextContent('Sobald die Verbindung zurück ist');
    expect(screen.queryByTestId('zahlungspflichtig-buchen')).toBeNull();
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(world.count('confirmBooking')).toBe(0);
    expect(world.count('startBookingPayment')).toBe(0);
    expect(zustand()).toHaveAttribute('data-state', 'offline');
  });

  it('checkout_abbruch_zeigt_ergebnis_wird_geprueft', async () => {
    const w = buchungsWelt();
    await w.client.call('createRideOffer', {} as never);
    const net = netz();
    render(
      <MemoryRouter initialEntries={['/checkout/x']}>
        <SessionProvider client={w.client as ApiClient}>
          <LiveRegionProvider>
            <ConnectionProvider source={net.source} offlineDelayMs={1}>
              <NetzBanner />
              <Routes>
                <Route
                  path="*"
                  element={
                    <Checkout
                      offerId={OFFER_ID}
                      zone="Europe/Berlin"
                      now={() => Date.parse('2026-11-20T07:50:00Z')}
                      pollMs={5}
                      maxPolls={6}
                      onNewSearch={() => undefined}
                    />
                  }
                />
              </Routes>
            </ConnectionProvider>
          </LiveRegionProvider>
        </SessionProvider>
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole('radio', { name: /gelingt/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
    w.world.failNext.set('confirmBooking', new ApiError(0));
    act(() => net.set(false)); // Verbindung bricht mitten in der Zahlung ab
    fireEvent.click(screen.getByTestId('zahlungspflichtig-buchen'));
    const unklar = await screen.findByTestId('checkout-unklar');
    expect(within(unklar).getByRole('heading', { name: 'Bestätigung wird geprüft' })).toBeVisible();
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(screen.queryByTestId('checkout-abgelehnt')).toBeNull();
    await waitFor(() => expect(zustand()).toHaveAttribute('data-state', 'offline'));
  });
});
