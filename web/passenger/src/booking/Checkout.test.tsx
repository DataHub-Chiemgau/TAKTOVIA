import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { LiveRegionProvider } from '@taktovia/design-system';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { OFFER_ID, NOW, buchungsWelt } from './buchungsWelt';
import type { PspVerweis } from './buchungsWelt';
import { Checkout } from './Checkout';

function renderCheckout(
  client: ApiClient,
  clock: { now: number } = { now: NOW },
  onNewSearch = vi.fn(),
) {
  const view = render(
    <MemoryRouter initialEntries={['/checkout/x']}>
      <SessionProvider client={client}>
        <LiveRegionProvider>
          <Routes>
            <Route
              path="*"
              element={
                <Checkout
                  offerId={OFFER_ID}
                  zone="Europe/Berlin"
                  now={() => clock.now}
                  pollMs={5}
                  maxPolls={6}
                  onNewSearch={onNewSearch}
                />
              }
            />
          </Routes>
        </LiveRegionProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, onNewSearch };
}

async function mitAngebot(options?: Parameters<typeof buchungsWelt>[0]) {
  const w = buchungsWelt(options);
  await w.client.call('createRideOffer', {} as never);
  return w;
}

/** Zahlungsmittel wählen, Bedingungen akzeptieren, Schaltfläche liefern. */
async function bereit(verweis: PspVerweis = 'pm_ok') {
  const radio = await screen.findByRole('radio', {
    name: new RegExp(
      { pm_ok: 'gelingt', pm_sca: 'Zusatzbestätigung', pm_decline: 'abgelehnt' }[
        verweis as 'pm_ok'
      ] ?? 'gelingt',
    ),
  });
  fireEvent.click(radio);
  fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
  return screen.getByTestId('zahlungspflichtig-buchen');
}

/** Ein Zahlungsmittel, das der Simulator-Auswahl fehlt (nur die Welt kennt es): direkt setzen, indem die Welt die Methode des Verweises überschreibt. */
function ueberschreibeMethode(world: ReturnType<typeof buchungsWelt>['world'], m: PspVerweis) {
  world.before.set('startBookingPayment', () => undefined);
  const orig = world.pay.set.bind(world.pay);
  world.pay.set = (k, v) => orig(k, { ...v, method: m });
}

describe('UI-03 Checkout (WP-M2-42)', () => {
  afterEach(cleanup);

  it('erfolgsfall_zeigt_buchungsnummer_und_zahlungsstatus_getrennt', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    // Zusammenfassung, Aufschlüsselung (Serverwerte), Stornoregel, Pflichtzustimmung getrennt, Betrag im Knopf
    expect(await screen.findByTestId('preis')).toHaveTextContent('9,50 €');
    expect(screen.getByTestId('preisaufschluesselung')).toHaveTextContent(
      'Fahrpreis (netto): 8,41 €',
    );
    expect(screen.getByTestId('stornoregel')).toHaveTextContent('std-24h');
    const knopf = await bereit();
    expect(knopf).toHaveTextContent('Zahlungspflichtig buchen – 9,50 €');
    fireEvent.click(knopf);
    const ok = await screen.findByTestId('checkout-erfolg');
    expect(within(ok).getByTestId('buchungsnummer')).toHaveTextContent('BK-2026-000001');
    expect(within(ok).getByTestId('buchung-status')).toHaveTextContent('Bestätigt');
    expect(within(ok).getByTestId('zahlungsstatus')).toHaveTextContent(
      'Betrag reserviert (noch nicht eingezogen)',
    );
    expect(within(ok).getByTestId('buchung-preis')).toHaveTextContent('9,50 €');
    // Hold, Buchung, Zahlung genau einmal, Betrag = Betrag des Angebots
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.count('confirmBooking')).toBe(1);
    expect(world.count('startBookingPayment')).toBe(1);
    const start = world.calls.find((c) => c.id === 'startBookingPayment')?.args as {
      body: { payment_method_ref: string; amount: unknown };
    };
    expect(start.body.payment_method_ref).toBe('pm_ok');
    expect(start.body.amount).toEqual(world.offer?.gross);
  });

  it('kein_buchen_ohne_zahlungsmittel_und_pflichtzustimmung', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    const knopf = await screen.findByTestId('zahlungspflichtig-buchen');
    expect(screen.getByTestId('checkout-voraussetzung')).toBeInTheDocument();
    fireEvent.click(knopf);
    expect(world.count('holdRideOffer')).toBe(0);
    fireEvent.click(await screen.findByRole('radio', { name: /gelingt/ }));
    fireEvent.click(knopf);
    expect(world.count('holdRideOffer')).toBe(0);
    fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
    fireEvent.click(knopf);
    await screen.findByTestId('checkout-erfolg');
  });

  it('hold_laeuft_waehrend_der_zahlung_ab_zeigt_keinen_erfolg', async () => {
    const clock = { now: NOW };
    const { client, world } = await mitAngebot();
    ueberschreibeMethode(world, 'pm_expired');
    renderCheckout(client, clock);
    fireEvent.click(await bereit());
    // Der Server hebt die verspätete Autorisierung auf und gibt die Buchung frei: kein Erfolg, Zahlstand sichtbar, neues Angebot
    const v = await screen.findByTestId('checkout-holdverlust');
    expect(v).toHaveTextContent('Reservierung abgelaufen');
    expect(within(v).getByTestId('zahlungsstatus')).toHaveTextContent('aufgehoben');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(within(v).getByRole('button', { name: 'Neue Suche' })).toBeInTheDocument();
  });

  it('countdown_zeigt_restfrist_und_ablauf_waehrend_sca_wird_gemeldet', async () => {
    const clock = { now: NOW };
    const { client } = await mitAngebot();
    renderCheckout(client, clock);
    fireEvent.click(await bereit('pm_sca'));
    const sca = await screen.findByTestId('checkout-sca');
    expect(within(sca).getByTestId('countdown')).toHaveTextContent(
      'Reservierung gültig noch: 03:00',
    );
    // die bestätigte Frist des Holds läuft ab, während die Bestätigung bei der Bank offen ist
    clock.now = Date.parse('2026-11-20T07:54:00Z');
    const hinweis = await screen.findByTestId('hold-abgelaufen-hinweis', {}, { timeout: 4000 });
    expect(hinweis).toHaveTextContent('Reservierungsfrist ist abgelaufen');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
  });

  it('sca_rueckkehr_stellt_abgleich_ein_und_erfolg_erst_nach_verifiziertem_status', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    fireEvent.click(await bereit('pm_sca'));
    const sca = await screen.findByTestId('checkout-sca');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    fireEvent.click(within(sca).getByRole('button', { name: /zurückgekehrt/ }));
    await screen.findByTestId('checkout-erfolg');
    expect(world.count('returnBookingPayment')).toBe(1);
    expect(world.count('startBookingPayment')).toBe(1);
  });

  it('sca_abbruch_zeigt_ablehnung_ohne_buchung', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    fireEvent.click(await bereit('pm_sca'));
    const sca = await screen.findByTestId('checkout-sca');
    // die Bank bricht ab: der PSP meldet die Ablehnung, die Buchung wird storniert
    const p = world.pay.get(world.bookings[0]?.id ?? '');
    if (p) p.method = 'pm_decline';
    fireEvent.click(within(sca).getByRole('button', { name: /zurückgekehrt/ }));
    const abl = await screen.findByTestId('checkout-abgelehnt');
    expect(abl).toHaveTextContent('Zahlung abgelehnt');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
  });

  it('zahlungsablehnung_zeigt_ablehnung_und_nichts_gebucht', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    fireEvent.click(await bereit('pm_decline'));
    const abl = await screen.findByTestId('checkout-abgelehnt');
    expect(abl).toHaveTextContent('nichts berechnet');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(world.bookings[0]?.state).toBe('CANCELED');
  });

  it('preisaenderung_requote_zeigt_neuen_preis_und_verlangt_ausdrueckliche_zustimmung', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    const knopf = await bereit();
    world.before.set('holdRideOffer', () => {
      if (world.offer) world.offer.gross = { amount_minor: 990, currency: 'EUR', exponent: 2 };
      world.before.delete('holdRideOffer');
    });
    fireEvent.click(knopf);
    const hinweis = await screen.findByTestId('hinweis-geaendert');
    expect(hinweis).toHaveTextContent('Neuer Preis: 9,90 €');
    expect(world.count('confirmBooking')).toBe(0);
    expect(world.bookings).toHaveLength(0);
    // Die Zustimmung ist zurückgenommen; ohne ausdrückliche Zustimmung zum neuen Preis wird nicht gebucht
    expect(
      screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }),
    ).not.toBeChecked();
    fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
    const neu = screen.getByTestId('zahlungspflichtig-buchen');
    expect(neu).toHaveTextContent('Zahlungspflichtig buchen – 9,90 €');
    fireEvent.click(neu);
    expect(world.count('holdRideOffer')).toBe(1);
    fireEvent.click(
      screen.getByRole('checkbox', { name: /geänderten Preis ausdrücklich zu: 9,90/ }),
    );
    fireEvent.click(neu);
    await screen.findByTestId('checkout-erfolg');
    expect(world.bookings[0]?.promise.gross.amount_minor).toBe(990);
  });

  it('revisionswechsel_412_wird_nicht_still_bestaetigt', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    const knopf = await bereit();
    world.failNext.set(
      'confirmBooking',
      new ApiError(412, {
        type: 'about:blank',
        title: 'x',
        status: 412,
        code: 'REVISION_MISMATCH',
        correlation_id: '77777777-7777-4777-8777-777777777777',
      }),
    );
    fireEvent.click(knopf);
    expect(await screen.findByTestId('hinweis-geaendert')).toBeInTheDocument();
    expect(world.bookings).toHaveLength(0);
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
  });

  it('abgelaufenes_angebot_wird_nicht_gebucht', async () => {
    const clock = { now: NOW };
    const { client, world } = await mitAngebot();
    const { onNewSearch } = renderCheckout(client, clock);
    const knopf = await bereit();
    // Uhr der Oberfläche steht noch vor dem Ablauf, der Server ist schon weiter
    world.before.set('holdRideOffer', () => {
      world.now = Date.parse('2026-11-20T08:31:00Z');
      clock.now = world.now;
    });
    fireEvent.click(knopf);
    const a = await screen.findByTestId('checkout-abgelaufen');
    expect(a).toHaveTextContent('Dieses Angebot ist abgelaufen');
    fireEvent.click(within(a).getByRole('button', { name: 'Neue Suche' }));
    expect(onNewSearch).toHaveBeenCalledTimes(1);
    expect(world.bookings).toHaveLength(0);
  });

  it('verbindungsabbruch_nach_absenden_behauptet_weder_erfolg_noch_ablehnung_und_wiederholt_mit_demselben_schluessel', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    const knopf = await bereit();
    world.failNext.set('confirmBooking', new ApiError(0));
    fireEvent.click(knopf);
    const u = await screen.findByTestId('checkout-unklar');
    expect(u).toHaveTextContent('Bestätigung wird geprüft');
    expect(u).toHaveTextContent('9,50 €');
    expect(within(u).getByRole('link', { name: 'Hilfe' })).toBeInTheDocument();
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(screen.queryByTestId('checkout-abgelehnt')).toBeNull();
    fireEvent.click(within(u).getByRole('button', { name: 'Stand prüfen' }));
    await screen.findByTestId('checkout-erfolg');
    const keys = world.calls
      .filter((c) => c.id === 'confirmBooking')
      .map((c) => (c.args as { headers: Record<string, string> }).headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.bookings).toHaveLength(1);
    expect(world.count('startBookingPayment')).toBe(1);
  });

  it('unbestimmtes_zahlungsergebnis_wird_als_geprueft_gezeigt', async () => {
    const { client, world } = await mitAngebot();
    ueberschreibeMethode(world, 'pm_unknown');
    renderCheckout(client);
    fireEvent.click(await bereit());
    const u = await screen.findByTestId('checkout-unklar');
    expect(u).toHaveTextContent('weder Erfolg noch Ablehnung');
    expect(screen.queryByTestId('checkout-erfolg')).toBeNull();
    expect(screen.queryByTestId('checkout-abgelehnt')).toBeNull();
  });

  it('doppelklick_und_neuladen_erzeugen_keine_zweite_buchung_und_keine_zweite_belastung', async () => {
    const { client, world } = await mitAngebot();
    const first = renderCheckout(client);
    const knopf = await bereit();
    fireEvent.click(knopf);
    fireEvent.click(knopf);
    await screen.findByTestId('checkout-erfolg');
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.count('confirmBooking')).toBe(1);
    expect(world.count('startBookingPayment')).toBe(1);
    // Neuladen: der Serverstand (Hold, Buchung, Zahlung) wird fortgesetzt, nie neu begonnen
    first.unmount();
    renderCheckout(client);
    fireEvent.click(await bereit());
    await screen.findByTestId('checkout-erfolg');
    expect(world.bookings).toHaveLength(1);
    expect(world.count('startBookingPayment')).toBe(1);
    expect(world.count('holdRideOffer')).toBe(1);
  });

  it('zwei_tabs_bestaetigen_dasselbe_angebot_genau_eine_buchung_und_eine_zahlung', async () => {
    const { client, world } = await mitAngebot();
    const a = renderCheckout(client);
    const ca = within(a.container);
    const b = renderCheckout(client);
    const cb = within(b.container);
    const prepare = async (c: ReturnType<typeof within>) => {
      fireEvent.click(await c.findByRole('radio', { name: /gelingt/ }));
      fireEvent.click(c.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
      return c.getByTestId('zahlungspflichtig-buchen');
    };
    const knopfA = await prepare(ca);
    const knopfB = await prepare(cb);
    // Tab A reserviert und bucht
    fireEvent.click(knopfA);
    await ca.findByTestId('checkout-erfolg');
    // Tab B hält den alten Angebotsstand (Revision): der Server weist ab (412), B zeigt den neuen Stand und verlangt erneute Zustimmung, bucht nie still
    fireEvent.click(knopfB);
    expect(await cb.findByTestId('hinweis-geaendert')).toBeInTheDocument();
    expect(world.bookings).toHaveLength(1);
    // erneute Zustimmung: der bestehende Hold wird bestätigt (dieselbe Buchung), die Zahlung nur beobachtet
    const knopfB2 = await prepare(cb);
    fireEvent.click(knopfB2);
    const okB = await cb.findByTestId('checkout-erfolg');
    expect(world.bookings).toHaveLength(1);
    expect(world.count('startBookingPayment')).toBe(1);
    expect(world.pay.size).toBe(1);
    expect(okB).toHaveTextContent(ca.getByTestId('buchungsnummer').textContent ?? 'x');
    a.unmount();
    b.unmount();
  });

  it('platz_nicht_mehr_verfuegbar_buchung_wird_nicht_behauptet', async () => {
    const { client, world } = await mitAngebot();
    renderCheckout(client);
    const knopf = await bereit();
    world.failNext.set(
      'holdRideOffer',
      new ApiError(409, {
        type: 'about:blank',
        title: 'x',
        status: 409,
        code: 'CAPACITY_UNAVAILABLE',
        correlation_id: '77777777-7777-4777-8777-777777777777',
      }),
    );
    fireEvent.click(knopf);
    expect(await screen.findByTestId('checkout-kapazitaet')).toHaveTextContent(
      'nicht mehr verfügbar',
    );
    expect(world.bookings).toHaveLength(0);
  });

  it('checkout_per_tastatur_und_screenreader_bedienbar', async () => {
    const { client } = await mitAngebot();
    const { container } = renderCheckout(client);
    const knopf = await bereit();
    // alle Bedienelemente sind benannt und fokussierbar, Gruppen tragen eine Beschriftung
    expect(screen.getByRole('group', { name: 'Zahlung' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Preisaufschlüsselung' })).toBeInTheDocument();
    knopf.focus();
    expect(document.activeElement).toBe(knopf);
    expect(knopf).toHaveAccessibleName(/^Zahlungspflichtig buchen – 9,50.€$/);
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
    // Während der Operation bleibt der Fokus erhalten, die Schaltfläche meldet sich als beschäftigt
    fireEvent.click(knopf);
    await screen.findByTestId('checkout-erfolg');
  });
});
