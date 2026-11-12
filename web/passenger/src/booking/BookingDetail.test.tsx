import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BookingDetail } from './BookingDetail';
import { BOOKING_ID, OTHER_BOOKING_ID, basisBuchung, reiseWelt, renderReise } from './reiseWelt';

const ZONE = 'Europe/Berlin';

function zeige(client: ReturnType<typeof reiseWelt>['client'], path?: string) {
  return renderReise(
    client,
    (id) => <BookingDetail bookingId={id} zone={ZONE} pollMs={10} />,
    path,
  );
}

describe('UI-04 Buchungsdetail mit Status und ETA ohne Neuladen (WP-M2-43)', () => {
  afterEach(cleanup);

  it('detail_zeigt_nummer_status_treffpunkt_eta_fahrzeug', async () => {
    const { client } = reiseWelt();
    zeige(client);
    expect(await screen.findByTestId('buchungsnummer')).toHaveTextContent('BK-2026-000001');
    // fachlicher Status und Zahlungsstatus getrennt
    expect(screen.getByTestId('buchung-status')).toHaveTextContent('Bestätigt');
    expect(screen.getByTestId('zahlungsstatus')).toHaveTextContent(
      'Betrag reserviert (noch nicht eingezogen)',
    );
    expect(screen.getByTestId('treffpunkt')).toHaveTextContent('Altmarkt');
    expect(screen.getByTestId('zugang')).toHaveTextContent('Stufenfreier Zugang: ja');
    // Zusage (Fenster) getrennt von der aktuellen ETA, beide beschriftet
    expect(screen.getByTestId('zusage-abholung')).toHaveTextContent(
      '21.11.2026, 08:55 bis 09:10 Uhr (UTC+01:00)',
    );
    expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:00 Uhr (UTC+01:00)');
    expect(screen.getByText('Verbindliche Zusage (Abholfenster)')).toBeInTheDocument();
    expect(screen.getByText('Aktuelle ETA (geplante Abholzeit)')).toBeInTheDocument();
    expect(screen.getByTestId('fahrzeug')).toHaveTextContent('Bus 12 (KLEINBUS)');
    expect(screen.getByTestId('letzter-stand')).toHaveTextContent('21.11.2026, 07:00 Uhr');
    expect(screen.queryByTestId('daten-veraltet')).toBeNull();
  });

  it('ohne_plan_noch_keine_eta_und_kein_fahrzeug_nie_geschaetzt', async () => {
    const { client } = reiseWelt(
      (() => {
        const { eta: _e, vehicle: _v, ...ohne } = basisBuchung({ state: 'CONFIRMING' });
        void _e;
        void _v;
        return ohne;
      })(),
    );
    zeige(client);
    expect(await screen.findByTestId('eta')).toHaveTextContent('Noch keine geplante Abholzeit');
    expect(screen.getByTestId('fahrzeug')).toHaveTextContent('Noch kein Fahrzeug zugewiesen');
  });

  it('eta_aenderung_erscheint_ohne_neuladen', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    await screen.findByTestId('eta');
    const reads = world.count('getBooking');
    world.planChange('2026-11-21T08:15:00Z');
    const hinweis = await screen.findByTestId('eta-aenderung');
    // alt -> neu, neuer Datenstand, kein erneutes Laden der Seite (kein weiterer Snapshot nötig)
    expect(hinweis).toHaveTextContent('21.11.2026, 09:00 Uhr (UTC+01:00)');
    expect(hinweis).toHaveTextContent('21.11.2026, 09:15 Uhr (UTC+01:00)');
    expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:15 Uhr (UTC+01:00)');
    expect(screen.getByTestId('letzter-stand')).toHaveTextContent('21.11.2026, 07:30 Uhr');
    expect(world.count('getBooking')).toBe(reads);
  });

  it('zusage_bleibt_bei_eta_aenderung_unveraendert', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    const zusage = (await screen.findByTestId('zusage-abholung')).textContent;
    const ankunft = screen.getByTestId('zusage-ankunft').textContent;
    world.planChange('2026-11-21T08:20:00Z');
    await screen.findByTestId('eta-aenderung');
    expect(screen.getByTestId('zusage-abholung').textContent).toBe(zusage);
    expect(screen.getByTestId('zusage-ankunft').textContent).toBe(ankunft);
    expect(screen.getByTestId('eta-aenderung')).toHaveTextContent(
      'Ihre verbindliche Zusage bleibt unverändert.',
    );
  });

  it('stromabbruch_kennzeichnet_daten_als_veraltet', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    await screen.findByTestId('eta');
    world.down = true;
    const v = await screen.findByTestId('daten-veraltet');
    expect(v).toHaveTextContent('Daten veraltet');
    // der zuletzt bekannte Stand bleibt sichtbar, wird aber als Stand von … ausgewiesen
    expect(v).toHaveTextContent('21.11.2026, 07:00 Uhr');
    expect(screen.getByTestId('eta')).toHaveTextContent('09:00');
  });

  it('wiederverbindung_laedt_aktuellen_stand', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    await screen.findByTestId('eta');
    world.down = true;
    await screen.findByTestId('daten-veraltet');
    // während der Unterbrechung ändert sich der Plan (Änderung geht nicht verloren)
    world.planChange('2026-11-21T08:25:00Z');
    world.down = false;
    await waitFor(() => expect(screen.queryByTestId('daten-veraltet')).toBeNull());
    await waitFor(() =>
      expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:25 Uhr (UTC+01:00)'),
    );
  });

  it('aeltere_revision_ueberschreibt_neuere_nicht', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    await screen.findByTestId('eta');
    // Revision 3 und 2 und 3 mehrfach, in unordentlicher Zustellung: Anzeige bleibt auf der höchsten Revision
    world.planChange('2026-11-21T08:10:00Z'); // sequence 2
    world.planChange('2026-11-21T08:20:00Z'); // sequence 3 (Serverstand)
    const veraltet = structuredClone(world.feed[world.feed.length - 2]);
    const neu = structuredClone(world.feed[world.feed.length - 1]);
    // Nachzügler (sequence 2) und Duplikat (sequence 3) werden nach dem Strom erneut zugestellt
    if (veraltet && neu) {
      veraltet.cursor = world.cursor + 1;
      neu.cursor = world.cursor + 2;
      world.cursor += 2;
      world.feed.push(veraltet, neu);
    }
    await waitFor(() =>
      expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:20 Uhr (UTC+01:00)'),
    );
    await new Promise((r) => setTimeout(r, 80));
    expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:20 Uhr (UTC+01:00)');
    expect(screen.getByTestId('eta')).not.toHaveTextContent('09:10');
  });

  it('eta_aenderung_fremder_buchung_erreicht_die_ansicht_nie', async () => {
    const { client, world } = reiseWelt(basisBuchung(), [
      basisBuchung({ id: OTHER_BOOKING_ID, booking_number: 'BK-2026-000009' }),
    ]);
    zeige(client);
    await screen.findByTestId('eta');
    world.entry(
      {
        kind: 'TIME_CHANGED',
        sequence: 2,
        pickup_before: '2026-11-21T08:00:00Z',
        pickup_after: '2026-11-21T09:30:00Z',
        dropoff_before: null,
        dropoff_after: null,
      },
      OTHER_BOOKING_ID,
    );
    await new Promise((r) => setTimeout(r, 80));
    expect(screen.queryByTestId('eta-aenderung')).toBeNull();
    expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:00 Uhr');
    expect(BOOKING_ID).not.toBe(OTHER_BOOKING_ID);
  });

  it('luecke_im_strom_laedt_den_snapshot', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    await screen.findByTestId('eta');
    // sequence 2 geht verloren: der Strom liefert sequence 3 (Lücke) -> Snapshot
    world.planChange('2026-11-21T08:10:00Z');
    world.feed.length = 0;
    world.planChange('2026-11-21T08:20:00Z');
    await waitFor(() =>
      expect(screen.getByTestId('eta')).toHaveTextContent('21.11.2026, 09:20 Uhr (UTC+01:00)'),
    );
  });

  it('fremde_buchung_zeigt_nicht_gefunden_ohne_daten', async () => {
    const { client } = reiseWelt();
    zeige(client, '/reisen/aaaaaaaa-0000-4000-8000-000000000000');
    const alert = await screen.findByRole('alert');
    expect(alert).toBeInTheDocument();
    expect(screen.queryByTestId('reise')).toBeNull();
    expect(screen.queryByText(/Altmarkt/)).toBeNull();
  });

  it('live_region_sagt_aenderung_an_ohne_fokusverschiebung', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    const link = await screen.findByRole('link', { name: 'Zu meinen Reisen' });
    link.focus();
    expect(document.activeElement).toBe(link);
    world.planChange('2026-11-21T08:30:00Z');
    await screen.findByTestId('eta-aenderung');
    // zurückhaltende Live-Region (polite, status) sagt die Änderung an
    const live = document.querySelector('[role="status"][aria-live="polite"]');
    await waitFor(() => expect(live).toHaveTextContent('Geplante Abholzeit geändert, neu'));
    expect(live).toHaveTextContent('09:30');
    // der Fokus bleibt, wo er war; die Änderung ist ein normaler Textabschnitt, kein Alarm
    expect(document.activeElement).toBe(link);
    expect(screen.getByTestId('eta-aenderung').getAttribute('role')).toBeNull();
  });

  it('detail_ist_ohne_verstoesse_bedienbar', async () => {
    const { client } = reiseWelt();
    const { container } = zeige(client);
    const art = await screen.findByTestId('reise');
    expect(within(art).getByRole('heading', { name: /Reise BK-2026-000001/ })).toBeInTheDocument();
    fireEvent.keyDown(art, { key: 'Tab' });
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('netzfehler_beim_laden_zeigt_fehlerzustand', async () => {
    const { client, world } = reiseWelt();
    world.failNext.set('getBooking', new ApiError(500));
    zeige(client);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
