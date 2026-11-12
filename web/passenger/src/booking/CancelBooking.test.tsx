import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError } from '@taktovia/api-client';
import type { RideBooking } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BookingDetail } from './BookingDetail';
import { CancelBooking } from './CancelBooking';
import { basisBuchung, reiseWelt, renderReise } from './reiseWelt';

const eur = (amount_minor: number) => ({ amount_minor, currency: 'EUR' as const, exponent: 2 });
const gezahlt = (status: 'AUTHORIZED' | 'CAPTURED'): Partial<RideBooking> => ({
  payment: {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    status,
    authorized: eur(950),
    captured: eur(status === 'CAPTURED' ? 950 : 0),
    refunded: eur(0),
    revision: 2,
  },
});

function zeige(client: ReturnType<typeof reiseWelt>['client']) {
  return renderReise(client, (id) => (
    <BookingDetail bookingId={id} zone="Europe/Berlin" pollMs={10}>
      {(b, reload) => <CancelBooking booking={b} zone="Europe/Berlin" onChanged={reload} />}
    </BookingDetail>
  ));
}

async function bestaetigen() {
  fireEvent.click(await screen.findByTestId('storno-starten'));
  return screen.findByTestId('storno-bestaetigen');
}

describe('UI-04 Selbststorno (WP-M2-46)', () => {
  afterEach(cleanup);

  it('regel_wird_vor_der_bestaetigung_gezeigt_und_ohne_bestaetigung_nicht_storniert', async () => {
    const { client, world } = reiseWelt(basisBuchung(gezahlt('AUTHORIZED')));
    zeige(client);
    const regel = await screen.findByTestId('storno-regel');
    expect(regel).toHaveTextContent('21.11.2026, 07:55 Uhr (UTC+01:00)');
    expect(regel).toHaveTextContent('60');
    expect(screen.getByTestId('storno-erwartung')).toHaveTextContent(
      'Der reservierte Betrag wird aufgehoben',
    );
    expect(screen.getByTestId('storno-erwartung')).toHaveTextContent('9,50 €');
    // erst nach ausdrücklicher Bestätigung mit Grundauswahl; Abbrechen storniert nichts
    fireEvent.click(screen.getByTestId('storno-starten'));
    const gruppe = await screen.findByRole('group');
    expect(within(gruppe).getByRole('combobox', { name: 'Grund' })).toHaveValue(
      'PASSENGER_REQUEST',
    );
    fireEvent.click(within(gruppe).getByRole('button', { name: 'Abbrechen' }));
    expect(world.count('cancelBooking')).toBe(0);
    expect(screen.queryByTestId('storno-ergebnis')).toBeNull();
  });

  it('storno_zeigt_status_und_erstattungshinweis', async () => {
    // nur autorisiert: Void, danach (PSP-Bestätigung) „aufgehoben“
    const a = reiseWelt(basisBuchung(gezahlt('AUTHORIZED')));
    zeige(a.client);
    fireEvent.click(await bestaetigen());
    const ergebnis = await screen.findByTestId('storno-ergebnis');
    expect(within(ergebnis).getByTestId('storno-status')).toHaveTextContent('storniert');
    expect(within(ergebnis).getByTestId('erstattungshinweis')).toHaveTextContent(
      'Die Reservierung des Betrags wird aufgehoben, es erfolgt keine Belastung: 9,50 €',
    );
    expect(screen.getByTestId('buchung-status')).toHaveTextContent('Storniert');
    // Request: genau die gelesene Revision als If-Match, Grund Kundenstorno
    const call = a.world.calls.find((c) => c.id === 'cancelBooking')?.args as {
      revision: number;
      body: { reason: string };
    };
    expect(call.revision).toBe(3);
    expect(call.body.reason).toBe('PASSENGER_REQUEST');
    cleanup();

    // eingezogen: Erstattung „beauftragt“ (nie „erstattet“ vor der PSP-Bestätigung), danach „erstattet“
    const b = reiseWelt(basisBuchung(gezahlt('CAPTURED')));
    zeige(b.client);
    expect(await screen.findByTestId('storno-erwartung')).toHaveTextContent(
      'Der eingezogene Betrag wird nach der Stornoregel erstattet',
    );
    fireEvent.click(await bestaetigen());
    const hinweis = await screen.findByTestId('erstattungshinweis');
    expect(hinweis).toHaveTextContent('Erstattung beauftragt, noch nicht bestätigt: 9,50 €');
    expect(hinweis).not.toHaveTextContent('Erstattet:');
    expect(screen.getByTestId('zahlungsstatus')).toHaveTextContent('Bezahlt');
    expect(b.world.booking.payment?.refund?.status).toBe('REQUESTED');
  });

  it('erstattet_erst_nach_psp_bestaetigung', async () => {
    const { client, world } = reiseWelt(basisBuchung(gezahlt('CAPTURED')));
    const { unmount } = zeige(client);
    fireEvent.click(await bestaetigen());
    expect(await screen.findByTestId('erstattungshinweis')).toHaveTextContent('beauftragt');
    unmount();
    world.pspBestaetigt();
    zeige(client);
    // die Ansicht liest den bestätigten Stand: „erstattet“ steht erst jetzt da
    expect(await screen.findByTestId('storno-ergebnis')).toBeInTheDocument();
    expect(await screen.findByTestId('erstattungshinweis')).toHaveTextContent('Erstattet: 9,50 €');
  });

  it('doppelklick_ein_storno', async () => {
    const { client, world } = reiseWelt(basisBuchung(gezahlt('AUTHORIZED')));
    zeige(client);
    const knopf = await bestaetigen();
    fireEvent.click(knopf);
    fireEvent.click(knopf);
    await screen.findByTestId('storno-ergebnis');
    expect(world.count('cancelBooking')).toBe(1);
    expect(world.stornos.size).toBe(1);
  });

  it('wiederholung_nach_verbindungsabbruch_nutzt_denselben_schluessel_genau_ein_storno', async () => {
    const { client, world } = reiseWelt(basisBuchung(gezahlt('CAPTURED')));
    zeige(client);
    world.failNext.set('cancelBooking', new ApiError(0));
    fireEvent.click(await bestaetigen());
    const unklar = await screen.findByTestId('storno-unklar');
    expect(unklar).toHaveTextContent('weder Erfolg noch Ablehnung');
    expect(screen.queryByTestId('storno-ergebnis')).toBeNull();
    fireEvent.click(within(unklar).getByRole('button', { name: 'Stand prüfen' }));
    await screen.findByTestId('storno-ergebnis');
    const keys = world.calls
      .filter((c) => c.id === 'cancelBooking')
      .map((c) => (c.args as { headers: Record<string, string> }).headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(world.stornos.size).toBe(1);
  });

  it('frist_ueberschritten_aktion_nicht_angeboten_und_serverseitig_abgewiesen', async () => {
    // laut Regel nicht angeboten
    const nicht = reiseWelt(
      basisBuchung({
        cancellation: {
          allowed: false,
          deadline_at: '2026-11-21T06:55:00Z',
          deadline_min: 60,
          deadline_defaulted: false,
        },
      }),
    );
    zeige(nicht.client);
    const text = await screen.findByTestId('storno-nicht-mehr');
    expect(text).toHaveTextContent('Storno nicht mehr möglich');
    expect(within(text).getByRole('link', { name: 'Hilfe' })).toBeInTheDocument();
    expect(screen.queryByTestId('storno-starten')).toBeNull();
    cleanup();
    // Frist läuft zwischen Anzeige und Klick ab: der Server weist ab, die Oberfläche zeigt „nicht mehr möglich“
    const { client, world } = reiseWelt();
    zeige(client);
    const knopf = await bestaetigen();
    world.fristAbgelaufen = true;
    fireEvent.click(knopf);
    expect(await screen.findByTestId('storno-nicht-mehr')).toBeInTheDocument();
    expect(screen.queryByTestId('storno-ergebnis')).toBeNull();
    expect(world.booking.state).toBe('CONFIRMED');
  });

  it('veralteter_stand_412_storniert_nicht_und_verlangt_erneute_pruefung', async () => {
    const { client, world } = reiseWelt();
    zeige(client);
    const knopf = await bestaetigen();
    // paralleler Eingriff ändert die Buchung (Revision) zwischen Anzeige und Klick
    world.booking = { ...world.booking, revision: world.booking.revision + 1 };
    fireEvent.click(knopf);
    expect(await screen.findByTestId('storno-geaendert')).toHaveTextContent(
      'Es wurde nichts storniert',
    );
    expect(world.booking.state).toBe('CONFIRMED');
    // nach erneuter Prüfung gilt die neue Revision
    fireEvent.click(await bestaetigen());
    await screen.findByTestId('storno-ergebnis');
    const calls = world.calls.filter((c) => c.id === 'cancelBooking');
    expect((calls[1]?.args as { revision: number }).revision).toBe(4);
  });

  it('bereits_storniert_zeigt_nur_status_und_hinweis_ohne_aktion', async () => {
    const { client } = reiseWelt(
      basisBuchung({
        state: 'CANCELED',
        bereich: 'storniert',
        payment: {
          id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          status: 'VOIDED',
          authorized: eur(950),
          captured: eur(0),
          refunded: eur(0),
          revision: 3,
        },
      }),
    );
    zeige(client);
    expect(await screen.findByTestId('storno-ergebnis')).toBeInTheDocument();
    expect(screen.getByTestId('erstattungshinweis')).toHaveTextContent('aufgehoben');
    expect(screen.queryByTestId('storno-starten')).toBeNull();
  });

  it('storno_per_tastatur_und_screenreader_bedienbar', async () => {
    const { client } = reiseWelt(basisBuchung(gezahlt('AUTHORIZED')));
    const { container } = zeige(client);
    const start = await screen.findByTestId('storno-starten');
    start.focus();
    expect(document.activeElement).toBe(start);
    fireEvent.click(start);
    const gruppe = await screen.findByRole('group', { name: /wirklich stornieren/ });
    expect(gruppe).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
    await waitFor(() => expect(screen.getByTestId('storno-bestaetigen')).toBeInTheDocument());
  });
});
