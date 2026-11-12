import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { RideBooking } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { NOW, SERVICE, buchungsWelt, renderBuchung } from './buchungsWelt';
import { waehleOrte } from './buchungsAblauf';
import { HAUPTSCHRITTE, MAX_HAUPTSCHRITTE } from './schritte';

const now = () => NOW;

function buchung(id: string, n: string, over: Partial<RideBooking> = {}): RideBooking {
  return {
    id,
    booking_number: n,
    service_id: SERVICE,
    hold_id: '88888888-8888-4888-8888-888888888880',
    state: 'CONFIRMED',
    created_at: '2026-11-19T07:51:00Z',
    passengers: 1,
    wheelchair_spaces: 0,
    revision: 1,
    promise: {
      pickup_earliest: '2026-11-21T07:55:00Z',
      pickup_latest: '2026-11-21T08:10:00Z',
      arrival_earliest: '2026-11-21T08:07:00Z',
      arrival_latest: '2026-11-21T08:22:00Z',
      gross: { amount_minor: 950, currency: 'EUR', exponent: 2 },
      cancellation_policy_ref: 'std-24h',
      resource_vector: { schema_version: '1.0', seats: 1, wheelchair: 0 },
    },
    ...over,
  };
}

describe('UI-01 Startansicht und Buchung in fünf Schritten (WP-M2-37)', () => {
  afterEach(cleanup);

  it('standardfahrt_in_hoechstens_fuenf_schritten', async () => {
    const { client, world } = buchungsWelt();
    const paths: string[] = [];
    renderBuchung(client, now, paths);
    const gesehen: string[] = [];
    const schritt = () => {
      const cur = document.querySelector('[data-testid="schritte"] [aria-current="step"]');
      const id = cur?.getAttribute('data-step');
      if (id && gesehen[gesehen.length - 1] !== id) gesehen.push(id);
    };
    await screen.findByRole('combobox', { name: 'Start' });
    schritt();
    // Schritt 1: Start und Ziel
    await waehleOrte();
    await screen.findByTestId('orte-bereit');
    schritt();
    // Schritt 2: Sofortfahrt, ein Fahrgast (Standardwerte), suchen
    expect(screen.getByRole('radio', { name: 'Jetzt losfahren' })).toBeChecked();
    expect(screen.getByRole('spinbutton', { name: 'Reisende' })).toHaveValue(1);
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    // Schritt 3: Angebot wählen
    const auswahl = await screen.findByRole('button', { name: /Angebot auswählen/ });
    schritt();
    fireEvent.click(auswahl);
    // Schritt 4: Buchung bestätigen
    fireEvent.click(await screen.findByRole('button', { name: /Verbindlich buchen/ }));
    const nummer = await screen.findByTestId('buchungsnummer');
    expect(nummer).toHaveTextContent('BK-2026-000001');
    // Zählung: genau die dokumentierten Hauptschritte, höchstens fünf, ohne Umweg über andere Bereiche
    expect(gesehen).toEqual(['orte', 'zeit', 'angebot']);
    expect(HAUPTSCHRITTE.map((s) => s.id)).toEqual(['orte', 'zeit', 'angebot', 'buchen']);
    expect(HAUPTSCHRITTE.length).toBeLessThanOrEqual(MAX_HAUPTSCHRITTE);
    expect(new Set(paths)).toEqual(new Set(['/', '/angebote']));
    // genau ein Versuch, ein Hold, eine Buchung
    expect(world.count('createRideOffer')).toBe(1);
    expect(world.count('holdRideOffer')).toBe(1);
    expect(world.count('confirmBooking')).toBe(1);
    expect(world.bookings).toHaveLength(1);
  });

  it('start_zeigt_anstehende_eigene_buchungen', async () => {
    const vergangen = buchung('00000000-0000-4000-8000-0000000000a2', 'BK-2026-000002', {
      promise: {
        ...buchung('x', 'x').promise,
        pickup_earliest: '2026-11-10T07:55:00Z',
        pickup_latest: '2026-11-10T08:10:00Z',
        arrival_earliest: '2026-11-10T08:07:00Z',
        arrival_latest: '2026-11-10T08:22:00Z',
      },
    });
    const storniert = buchung('00000000-0000-4000-8000-0000000000a3', 'BK-2026-000003', {
      state: 'CANCELED',
    });
    const offen = buchung('00000000-0000-4000-8000-0000000000a1', 'BK-2026-000001');
    const { client, world } = buchungsWelt({ bookings: [vergangen, storniert, offen] });
    renderBuchung(client, now);
    const liste = await screen.findByTestId('anstehende-buchungen');
    await waitFor(() => expect(within(liste).getByText('BK-2026-000001')).toBeInTheDocument());
    expect(within(liste).queryByText('BK-2026-000002')).toBeNull();
    expect(within(liste).queryByText('BK-2026-000003')).toBeNull();
    // Start, Ziel, Reisezeit und Verfügbarkeit sind sichtbar
    expect(screen.getByRole('combobox', { name: 'Start' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Ziel' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Jetzt losfahren' })).toBeInTheDocument();
    expect(await screen.findByText('Der Dienst ist in Betrieb.')).toBeInTheDocument();
    // Statuswechsel während der Anzeige: nach dem Aktualisieren ist die Buchung nicht mehr „anstehend“
    world.bookings[2] = { ...offen, state: 'CANCELED' };
    fireEvent.click(within(liste).getByRole('button', { name: 'Aktualisieren' }));
    await waitFor(() =>
      expect(within(liste).getByText('Sie haben keine anstehenden Buchungen.')).toBeInTheDocument(),
    );
  });

  it('start_zeigt_leerzustand_ohne_buchungen_und_dienst_ausser_betrieb_mit_naechster_betriebszeit', async () => {
    const { client } = buchungsWelt({ closed: true });
    renderBuchung(client, now);
    expect(await screen.findByText('Sie haben keine anstehenden Buchungen.')).toBeInTheDocument();
    const v = await screen.findByTestId('verfuegbarkeit');
    await waitFor(() => expect(within(v).getByText(/außer Betrieb/)).toBeInTheDocument());
    expect(v).toHaveTextContent('Nächste Betriebszeit');
    expect(v).toHaveTextContent('21.11.2026, 06:00 Uhr (UTC+01:00)');
  });

  it('leere_angebotsliste_nennt_ablehnungsgrund_und_naechste_handlung', async () => {
    const { client, world } = buchungsWelt({ rejection: 'OUTSIDE_AREA' });
    renderBuchung(client, now);
    await waehleOrte();
    fireEvent.click(await screen.findByRole('button', { name: 'Verbindungen suchen' }));
    const a = await screen.findByTestId('ablehnung');
    expect(within(a).getByRole('heading', { name: 'Keine Abdeckung' })).toBeInTheDocument();
    expect(a).toHaveTextContent('Start oder Ziel liegt außerhalb des Bediengebiets.');
    expect(a).toHaveTextContent(
      'Nächste Handlung: Wählen Sie Start und Ziel innerhalb des Bediengebiets.',
    );
    // nichts gebucht
    expect(world.count('holdRideOffer') + world.count('confirmBooking')).toBe(0);
  });

  it('ausserhalb_betriebszeit_zeigt_keine_abdeckung_mit_grund', async () => {
    const { client } = buchungsWelt({ rejection: 'OUTSIDE_HOURS' });
    renderBuchung(client, now);
    await waehleOrte();
    fireEvent.click(await screen.findByRole('button', { name: 'Verbindungen suchen' }));
    const a = await screen.findByTestId('ablehnung');
    expect(within(a).getByRole('heading', { name: 'Keine Abdeckung' })).toBeInTheDocument();
    expect(a).toHaveTextContent('Der Dienst ist zur gewünschten Zeit nicht in Betrieb.');
    expect(a).toHaveTextContent('Wählen Sie eine Zeit innerhalb der Betriebszeiten.');
  });

  it('dst_mehrdeutige_zeit_verlangt_offsetauswahl', async () => {
    const { client, world } = buchungsWelt();
    renderBuchung(client, now);
    await waehleOrte();
    await screen.findByText('Der Dienst ist in Betrieb.');
    fireEvent.click(screen.getByRole('radio', { name: 'Zeit wählen' }));
    const feld = await screen.findByLabelText('Datum und Uhrzeit');
    // 25.10.2026 02:30 kommt in Berlin zweimal vor (Herbstumstellung)
    fireEvent.change(feld, { target: { value: '2026-10-25T02:30' } });
    const gruppe = await screen.findByRole('group', { name: 'UTC-Offset wählen' });
    expect(within(gruppe).getByText(/\+02:00/)).toBeInTheDocument();
    expect(within(gruppe).getByText(/\+01:00/)).toBeInTheDocument();
    expect(screen.getByTestId('zone')).toHaveTextContent('Europe/Berlin');
    // ohne Offsetwahl wird nicht gesucht
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    expect(screen.getByTestId('such-hinweis')).toHaveTextContent(
      'Bitte geben Sie eine gültige Zeit an',
    );
    expect(world.count('createRideOffer')).toBe(0);
    // mit Offsetwahl geht die Suche mit genau diesem Offset ab
    fireEvent.click(within(gruppe).getByRole('radio', { name: /\+01:00/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    await waitFor(() => expect(world.count('createRideOffer')).toBe(1));
    const body = world.calls.find((c) => c.id === 'createRideOffer')?.args as {
      body: { desired: { desired_at: string; time_zone: string }; time_kind: string };
    };
    expect(body.body.desired).toEqual({
      desired_at: '2026-10-25T02:30:00+01:00',
      time_zone: 'Europe/Berlin',
    });
    expect(body.body.time_kind).toBe('DEPARTURE');
  });

  it('nicht_existente_ortszeit_verlangt_korrektur', async () => {
    const { client, world } = buchungsWelt();
    renderBuchung(client, now);
    await waehleOrte();
    await screen.findByText('Der Dienst ist in Betrieb.');
    fireEvent.click(screen.getByRole('radio', { name: 'Zeit wählen' }));
    // 29.03.2026 02:30 existiert in Berlin nicht (Frühjahrslücke)
    fireEvent.change(await screen.findByLabelText('Datum und Uhrzeit'), {
      target: { value: '2026-03-29T02:30' },
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'existiert wegen der Zeitumstellung nicht',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    expect(world.count('createRideOffer')).toBe(0);
    // Korrektur führt zur Suche (Ankunftswunsch)
    fireEvent.click(screen.getByRole('radio', { name: 'Ankunft' }));
    fireEvent.change(screen.getByLabelText('Datum und Uhrzeit'), {
      target: { value: '2026-03-29T03:30' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verbindungen suchen' }));
    await waitFor(() => expect(world.count('createRideOffer')).toBe(1));
  });

  it('abbruch_der_suche_bestaetigt_keine_buchung', async () => {
    const { client, world } = buchungsWelt({ searchDelayMs: 5000 });
    renderBuchung(client, now);
    await waehleOrte();
    fireEvent.click(await screen.findByRole('button', { name: 'Verbindungen suchen' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Suche abbrechen' }));
    expect(await screen.findByText(/Die Suche wurde abgebrochen/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suche abbrechen' })).toBeNull();
    expect(world.count('holdRideOffer')).toBe(0);
    expect(world.count('confirmBooking')).toBe(0);
    expect(world.bookings).toHaveLength(0);
    // danach ist eine neue Suche wieder möglich
    expect(screen.getByRole('button', { name: 'Verbindungen suchen' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('doppelklick_auf_suchen_sendet_genau_eine_anfrage', async () => {
    const { client, world } = buchungsWelt({ searchDelayMs: 50 });
    renderBuchung(client, now);
    await waehleOrte();
    const knopf = await screen.findByRole('button', { name: 'Verbindungen suchen' });
    fireEvent.click(knopf);
    fireEvent.click(knopf);
    await screen.findByRole('button', { name: /Angebot auswählen/ });
    expect(world.count('createRideOffer')).toBe(1);
  });

  it('startansicht_per_tastatur_und_screenreader_bedienbar', async () => {
    const { client } = buchungsWelt({
      bookings: [buchung('00000000-0000-4000-8000-0000000000a1', 'BK-2026-000001')],
    });
    const { container } = renderBuchung(client, now);
    await waehleOrte();
    await screen.findByText('BK-2026-000001');
    await screen.findByText('Der Dienst ist in Betrieb.');
    // jede Eingabe hat einen zugänglichen Namen und ist fokussierbar (natives Formularelement, kein tabindex=-1)
    const namen = ['Reisende', 'Rollstuhlplatz benötigt', 'Jetzt losfahren', 'Zeit wählen'];
    for (const n of namen) {
      const el = screen.getByLabelText(n);
      el.focus();
      expect(document.activeElement).toBe(el);
      expect(el.getAttribute('tabindex')).not.toBe('-1');
    }
    // Schritte und Abschnitte sind als Liste bzw. benannte Bereiche auszeichnet
    expect(screen.getByRole('list', { name: /Buchung in 4 Schritten/ })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Verfügbarkeit' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Meine anstehenden Buchungen' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Wann möchten Sie fahren?' })).toBeInTheDocument();
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
  });
});
