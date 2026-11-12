import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { OFFER_ID, NOW, buchungsWelt } from './buchungsWelt';
import { Checkout } from './Checkout';
import { OfferList } from './OfferList';
import { formatMoney } from './geld';

const ORIGIN = { label: 'Start', point: { latitude: 51.05, longitude: 13.74 } };
const DEST = { label: 'Ziel', point: { latitude: 51.04, longitude: 13.74 } };

function renderOffers(client: ApiClient, clock: { now: number }, onNewSearch = vi.fn()) {
  const view = render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <OfferList
          offerIds={[OFFER_ID]}
          origin={ORIGIN}
          destination={DEST}
          zone="Europe/Berlin"
          directSeconds={{ [OFFER_ID]: 720 }}
          now={() => clock.now}
          onNewSearch={onNewSearch}
        />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, onNewSearch };
}

/** Das Angebot muss in der Welt existieren: die Suche legt es an. */
async function mitAngebot(options?: Parameters<typeof buchungsWelt>[0]) {
  const w = buchungsWelt(options);
  await w.client.call('createRideOffer', {} as never);
  return w;
}

async function waehle() {
  fireEvent.click(await screen.findByRole('button', { name: /Angebot auswählen/ }));
  return screen.findByRole('link', { name: /Weiter zur Buchung/ });
}

function CheckoutRoute({ clock }: { clock: { now: number } }) {
  const { offerId } = useParams();
  return (
    <Checkout
      offerId={offerId ?? ''}
      zone="Europe/Berlin"
      now={() => clock.now}
      pollMs={5}
      maxPolls={6}
      onNewSearch={() => undefined}
    />
  );
}

/** Angebotsliste und Checkout in einem Router: die Auswahl der Liste führt in den Checkout (Übergabe der Angebots-ID in der Adresse). */
function renderListeUndCheckout(client: ApiClient, clock: { now: number }) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider client={client}>
        <Routes>
          <Route
            path="/"
            element={
              <OfferList
                offerIds={[OFFER_ID]}
                origin={ORIGIN}
                destination={DEST}
                zone="Europe/Berlin"
                directSeconds={{ [OFFER_ID]: 720 }}
                now={() => clock.now}
                onNewSearch={vi.fn()}
              />
            }
          />
          <Route path="/checkout/:offerId" element={<CheckoutRoute clock={clock} />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

/** Wählt in der Liste das Angebot, wechselt in den Checkout und bereitet die Buchung vor (Zahlungsmittel, Zustimmung). */
async function inDenCheckout() {
  fireEvent.click(await waehle());
  fireEvent.click(await screen.findByRole('radio', { name: /gelingt/ }));
  fireEvent.click(screen.getByRole('checkbox', { name: /Beförderungs- und Stornobedingungen/ }));
  return screen.getByTestId('zahlungspflichtig-buchen');
}

describe('UI-02 Angebotsliste und Angebotsdetails (WP-M2-41)', () => {
  afterEach(cleanup);

  it('angebot_zeigt_fusswegzeitfenster_dauer_preis', async () => {
    const { client } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const karte = await screen.findByTestId('angebot');
    expect(
      within(karte).getByRole('heading', { name: /Altmarkt → Hauptbahnhof/ }),
    ).toBeInTheDocument();
    // Fußweg: Luftlinie zwischen gewähltem Ort und Haltepunkt, gekennzeichnet als Näherung (hier Start 51,05 / Haltepunkt 51,0505: 56 m, 1 min)
    expect(within(karte).getByTestId('fussweg-start')).toHaveTextContent(
      '56 m, ca. 1 min (Luftlinie, ca.)',
    );
    expect(within(karte).getByTestId('fussweg-ziel')).toHaveTextContent(
      '56 m, ca. 1 min (Luftlinie, ca.)',
    );
    expect(within(karte).getByTestId('abholfenster')).toHaveTextContent(
      '20.11.2026, 08:55 bis 09:10 Uhr (UTC+01:00)',
    );
    expect(within(karte).getByTestId('ankunftsfenster')).toHaveTextContent(
      '20.11.2026, 09:07 bis 09:22 Uhr (UTC+01:00)',
    );
    expect(within(karte).getByTestId('fahrtdauer')).toHaveTextContent('12 min');
    expect(within(karte).getByTestId('preis')).toHaveTextContent('9,50 €');
    expect(karte).toHaveTextContent('Angebot gültig bis');
    expect(karte).toHaveTextContent('20.11.2026, 09:30 Uhr (UTC+01:00)');
    // unbestätigte Barrierefreiheit des Haltepunkts (wheelchair_boarding UNVERIFIED) wird gezeigt
    expect(within(karte).getByTestId('barrierefreiheit-unbestaetigt')).toBeInTheDocument();
    // Details mit Preisaufschlüsselung und Bedingungen nach der Auswahl
    fireEvent.click(within(karte).getByRole('button', { name: /Angebot auswählen/ }));
    const d = await screen.findByTestId('details');
    expect(d).toHaveTextContent('Fahrpreis (netto): 8,41 €');
    expect(d).toHaveTextContent('Umsatzsteuer (enthalten): 0,62 €');
    expect(d).toHaveTextContent('Stornoregel: std-24h');
  });

  it('fussweg_des_angebots_ersetzt_die_naeherung_und_kennzeichnet_den_rueckfall', async () => {
    const { client, world } = await mitAngebot();
    if (world.offer)
      world.offer.walk = {
        status: 'ROUTE',
        snapshot_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccc01',
        distance_m: 310,
        duration_s: 250,
        profile: 'walk',
        map_version: 'referenz-2026-11-01',
        fallback_reason: null,
        geometry: [],
        steps: [],
        pickup_stop_name: 'Altmarkt',
        computed_at: '2026-11-20T07:50:00Z',
      };
    renderOffers(client, { now: NOW });
    expect(await screen.findByTestId('fussweg-start')).toHaveTextContent('310 m, ca. 5 min');
    expect(screen.getByTestId('fussweg-start')).not.toHaveTextContent('Luftlinie');
  });

  it('preis_wird_clientseitig_nicht_berechnet_oder_gerundet', async () => {
    // Darstellung ist reine Zerlegung des Serverwerts
    expect(formatMoney({ amount_minor: 1, currency: 'EUR', exponent: 2 })).toBe('0,01 €');
    expect(formatMoney({ amount_minor: 123456789, currency: 'EUR', exponent: 2 })).toBe(
      '1.234.567,89 €',
    );
    expect(formatMoney({ amount_minor: -50, currency: 'EUR', exponent: 2 })).toBe('−0,50 €');
    expect(formatMoney({ amount_minor: 7, currency: 'CHF', exponent: 0 })).toBe('7 CHF');
    // die Komponenten summieren sich hier absichtlich nicht zum Gesamtpreis (841+47+62=950): angezeigt wird der Serverwert 9,50 €, nie eine Summe
    const { client, world } = await mitAngebot();
    if (world.offer) world.offer.gross = { amount_minor: 951, currency: 'EUR', exponent: 2 };
    renderOffers(client, { now: NOW });
    expect(await screen.findByTestId('preis')).toHaveTextContent('9,51 €');
    fireEvent.click(screen.getByRole('button', { name: /Angebot auswählen/ }));
    const d = await screen.findByTestId('details');
    expect(d).toHaveTextContent('Gesamtpreis: 9,51 €');
    // die Auswahl führt zum Checkout und trägt genau den Serverbetrag im Namen
    expect(
      await screen.findByRole('link', {
        name: /^Weiter zur Buchung: Altmarkt → Hauptbahnhof, 9,51.€$/,
      }),
    ).toHaveAttribute('href', `/checkout/${OFFER_ID}`);
  });

  it('angezeigter_stand_gleich_bestaetigter_buchung', async () => {
    const { client, world } = await mitAngebot();
    renderListeUndCheckout(client, { now: NOW });
    const karte = await screen.findByTestId('angebot');
    const gezeigt = {
      abholung: within(karte).getByTestId('abholfenster').textContent,
      ankunft: within(karte).getByTestId('ankunftsfenster').textContent,
      preis: within(karte).getByTestId('preis').textContent,
      revision: karte.getAttribute('data-revision'),
    };
    fireEvent.click(await inDenCheckout());
    const b = await screen.findByTestId('checkout-erfolg');
    // Anzeige der Liste = Bestätigung = promise der entstandenen Buchung = Angebotsstand
    expect(within(b).getByTestId('buchung-abholfenster').textContent).toBe(gezeigt.abholung);
    expect(within(b).getByTestId('buchung-preis').textContent).toBe(gezeigt.preis);
    const promise = world.bookings[0]?.promise;
    expect(promise?.gross).toEqual(world.offer?.gross);
    expect(promise?.pickup_earliest).toBe(world.offer?.pickup_window.earliest_at);
    expect(promise?.arrival_latest).toBe(world.offer?.arrival_window.latest_at);
    const hold = world.calls.find((c) => c.id === 'holdRideOffer')?.args as {
      revision: number;
      path: { ride_offer_id: string };
    };
    expect(String(hold.revision)).toBe(gezeigt.revision);
    expect(hold.path.ride_offer_id).toBe(OFFER_ID);
    // die Bestätigung trägt die Revision nach dem Hold (ETag)
    const confirm = world.calls.find((c) => c.id === 'confirmBooking')?.args as {
      revision: number;
    };
    expect(confirm.revision).toBe(Number(gezeigt.revision) + 1);
  });

  it('geaendertes_angebot_wird_nicht_still_bestaetigt', async () => {
    const { client, world } = await mitAngebot();
    renderListeUndCheckout(client, { now: NOW });
    const knopf = await inDenCheckout();
    // paralleler Vorgang ändert das Angebot zwischen Anzeige und Bestätigung (Revision)
    world.before.set('holdRideOffer', () => {
      if (world.offer) world.offer.revision += 1;
      world.before.delete('holdRideOffer');
    });
    fireEvent.click(knopf);
    expect(await screen.findByTestId('hinweis-geaendert')).toHaveTextContent(
      'Das Angebot hat sich geändert',
    );
    expect(world.count('confirmBooking')).toBe(0);
    expect(world.bookings).toHaveLength(0);
    expect(screen.getByTestId('checkout').getAttribute('data-revision')).toBe('2');
  });

  it('revisionswechsel_412_wird_nicht_still_bestaetigt', async () => {
    const { client, world } = await mitAngebot();
    renderListeUndCheckout(client, { now: NOW });
    const knopf = await inDenCheckout();
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

  it('requote_required_zeigt_neuen_stand', async () => {
    const { client, world } = await mitAngebot();
    renderListeUndCheckout(client, { now: NOW });
    const knopf = await inDenCheckout();
    world.before.set('holdRideOffer', () => {
      if (world.offer) world.offer.gross = { amount_minor: 990, currency: 'EUR', exponent: 2 };
      world.before.delete('holdRideOffer');
    });
    fireEvent.click(knopf);
    expect(await screen.findByTestId('hinweis-geaendert')).toHaveTextContent('Neuer Preis: 9,90 €');
    expect(screen.getByTestId('preis')).toHaveTextContent('9,90 €');
    expect(world.bookings).toHaveLength(0);
  });

  it('abgelaufenes_angebot_verlangt_neue_auswahl', async () => {
    const clock = { now: NOW };
    const { client, world } = await mitAngebot();
    const { onNewSearch } = renderOffers(client, clock);
    await waehle();
    // das Angebot läuft während der Anzeige ab (Ablauf 08:30 UTC)
    clock.now = Date.parse('2026-11-20T08:31:00Z');
    const hinweis = await screen.findByTestId('hinweis-abgelaufen', {}, { timeout: 4000 });
    expect(hinweis).toHaveTextContent('Dieses Angebot ist abgelaufen');
    expect(screen.queryByRole('link', { name: /Weiter zur Buchung/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Angebot auswählen/ })).toBeNull();
    fireEvent.click(within(hinweis).getByRole('button', { name: 'Neue Suche' }));
    expect(onNewSearch).toHaveBeenCalledTimes(1);
    expect(world.count('holdRideOffer')).toBe(0);
  });

  it('angebotsliste_per_tastatur_und_screenreader_bedienbar', async () => {
    const { client } = await mitAngebot();
    const { container } = renderOffers(client, { now: NOW });
    const karte = await screen.findByTestId('angebot');
    // Karte ist ein benannter Artikel, die Fakten sind eine Definitionsliste, die Auswahl ein Button mit vollem Namen
    expect(screen.getByRole('article', { name: /Altmarkt → Hauptbahnhof/ })).toBe(karte);
    const auswahl = screen.getByRole('button', {
      name: /^Angebot auswählen: Altmarkt → Hauptbahnhof, 9,50.€$/,
    });
    auswahl.focus();
    expect(document.activeElement).toBe(auswahl);
    fireEvent.click(auswahl);
    const buchen = await screen.findByRole('link', {
      name: /^Weiter zur Buchung: Altmarkt → Hauptbahnhof, 9,50.€$/,
    });
    buchen.focus();
    expect(document.activeElement).toBe(buchen);
    expect(screen.getByRole('heading', { name: 'Preisaufschlüsselung' })).toBeInTheDocument();
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
    await waitFor(() => expect(karte).toBeInTheDocument());
  });
});
