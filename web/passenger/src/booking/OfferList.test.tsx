import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { OFFER_ID, NOW, buchungsWelt } from './buchungsWelt';
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
  return screen.findByRole('button', { name: /Verbindlich buchen/ });
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
    // Hold und Bestätigung tragen genau den Serverbetrag
    fireEvent.click(await screen.findByRole('button', { name: /^Verbindlich buchen – 9,51.€$/ }));
    await screen.findByTestId('buchung-bestaetigt');
    const hold = world.calls.find((c) => c.id === 'holdRideOffer')?.args as {
      body: { max_gross: unknown };
    };
    expect(hold.body.max_gross).toEqual({ amount_minor: 951, currency: 'EUR', exponent: 2 });
  });

  it('angezeigter_stand_gleich_bestaetigter_buchung', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const karte = await screen.findByTestId('angebot');
    const gezeigt = {
      abholung: within(karte).getByTestId('abholfenster').textContent,
      ankunft: within(karte).getByTestId('ankunftsfenster').textContent,
      preis: within(karte).getByTestId('preis').textContent,
      revision: karte.getAttribute('data-revision'),
    };
    fireEvent.click(within(karte).getByRole('button', { name: /Angebot auswählen/ }));
    fireEvent.click(await screen.findByRole('button', { name: /Verbindlich buchen/ }));
    const b = await screen.findByTestId('buchung-bestaetigt');
    // Anzeige der Bestätigung = Anzeige des Angebots = promise der entstandenen Buchung = Angebotsstand
    expect(within(b).getByTestId('buchung-abholfenster').textContent).toBe(gezeigt.abholung);
    expect(within(b).getByTestId('buchung-ankunftsfenster').textContent).toBe(gezeigt.ankunft);
    expect(within(b).getByTestId('buchung-preis').textContent).toBe(gezeigt.preis);
    const promise = world.bookings[0]?.promise;
    expect(promise?.gross).toEqual(world.offer?.gross);
    expect(promise?.pickup_earliest).toBe(world.offer?.pickup_window.earliest_at);
    expect(promise?.arrival_latest).toBe(world.offer?.arrival_window.latest_at);
    // Hold bekam die angezeigte Revision unverändert, die Bestätigung die Revision nach dem Hold (ETag)
    const hold = world.calls.find((c) => c.id === 'holdRideOffer')?.args as {
      revision: number;
      path: { ride_offer_id: string };
    };
    expect(String(hold.revision)).toBe(gezeigt.revision);
    expect(hold.path.ride_offer_id).toBe(OFFER_ID);
    const confirm = world.calls.find((c) => c.id === 'confirmBooking')?.args as {
      revision: number;
    };
    expect(confirm.revision).toBe(Number(gezeigt.revision) + 1);
    expect(within(b).getByTestId('buchung-status')).toHaveTextContent(
      'Wird bestätigt (Zahlung ausstehend)',
    );
  });

  it('geaendertes_angebot_wird_nicht_still_bestaetigt', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const knopf = await waehle();
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
    // erneute Auswahl nötig: die Auswahl ist zurückgenommen, der neue Stand wird angezeigt
    expect(screen.queryByRole('button', { name: /Verbindlich buchen/ })).toBeNull();
    expect(screen.getByTestId('angebot').getAttribute('data-revision')).toBe('2');
    // nach erneuter Auswahl wird genau der neue Stand gebucht
    fireEvent.click(await waehle());
    await screen.findByTestId('buchung-bestaetigt');
    expect(world.bookings).toHaveLength(1);
  });

  it('revisionswechsel_412_wird_nicht_still_bestaetigt', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const knopf = await waehle();
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
    expect(screen.queryByTestId('buchung-bestaetigt')).toBeNull();
    // der Neustand wurde gelesen, der Fahrgast muss erneut wählen
    expect(await screen.findByRole('button', { name: /Angebot auswählen/ })).toBeInTheDocument();
  });

  it('requote_required_zeigt_neuen_stand', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const knopf = await waehle();
    world.before.set('holdRideOffer', () => {
      if (world.offer) world.offer.gross = { amount_minor: 990, currency: 'EUR', exponent: 2 };
      world.before.delete('holdRideOffer');
    });
    fireEvent.click(knopf);
    const hinweis = await screen.findByTestId('hinweis-geaendert');
    expect(hinweis).toHaveTextContent('Neuer Preis: 9,90 €');
    expect(screen.getByTestId('preis')).toHaveTextContent('9,90 €');
    expect(world.count('confirmBooking')).toBe(0);
    expect(world.bookings).toHaveLength(0);
    // der Fahrgast stimmt ausdrücklich zu: erneute Auswahl, dann Buchung zum neuen Preis
    fireEvent.click(await screen.findByRole('button', { name: /Angebot auswählen/ }));
    fireEvent.click(await screen.findByRole('button', { name: /^Verbindlich buchen – 9,90.€$/ }));
    await screen.findByTestId('buchung-bestaetigt');
    expect(world.bookings[0]?.promise.gross.amount_minor).toBe(990);
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
    expect(screen.queryByRole('button', { name: /Verbindlich buchen/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Angebot auswählen/ })).toBeNull();
    fireEvent.click(within(hinweis).getByRole('button', { name: 'Neue Suche' }));
    expect(onNewSearch).toHaveBeenCalledTimes(1);
    expect(world.count('holdRideOffer')).toBe(0);
  });

  it('ablauf_beim_buchen_wird_serverseitig_abgewiesen_und_zeigt_abgelaufen', async () => {
    const clock = { now: NOW };
    const { client, world } = await mitAngebot();
    renderOffers(client, clock);
    const knopf = await waehle();
    // Uhr der Oberfläche steht noch vor dem Ablauf, der Server ist schon weiter
    world.before.set('holdRideOffer', () => {
      world.now = Date.parse('2026-11-20T08:31:00Z');
      clock.now = world.now;
    });
    fireEvent.click(knopf);
    expect(await screen.findByTestId('hinweis-abgelaufen')).toBeInTheDocument();
    expect(world.bookings).toHaveLength(0);
  });

  it('unklares_ergebnis_behauptet_weder_erfolg_noch_ablehnung_und_wiederholt_mit_demselben_schluessel', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const knopf = await waehle();
    world.failNext.set('confirmBooking', new ApiError(0));
    fireEvent.click(knopf);
    expect(await screen.findByTestId('hinweis-unklar')).toHaveTextContent(
      'weder Erfolg noch Ablehnung',
    );
    expect(screen.queryByTestId('buchung-bestaetigt')).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'Stand prüfen' }));
    await screen.findByTestId('buchung-bestaetigt');
    const keys = world.calls
      .filter((c) => c.id === 'confirmBooking')
      .map((c) => (c.args as { headers: Record<string, string> }).headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.bookings).toHaveLength(1);
  });

  it('doppelklick_auf_buchen_erzeugt_genau_eine_buchung', async () => {
    const { client, world } = await mitAngebot();
    renderOffers(client, { now: NOW });
    const knopf = await waehle();
    fireEvent.click(knopf);
    fireEvent.click(knopf);
    await screen.findByTestId('buchung-bestaetigt');
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.count('confirmBooking')).toBe(1);
    expect(world.bookings).toHaveLength(1);
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
    const buchen = await screen.findByRole('button', { name: /^Verbindlich buchen – 9,50.€$/ });
    buchen.focus();
    expect(document.activeElement).toBe(buchen);
    expect(screen.getByRole('heading', { name: 'Preisaufschlüsselung' })).toBeInTheDocument();
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
    await waitFor(() => expect(karte).toBeInTheDocument());
  });
});
