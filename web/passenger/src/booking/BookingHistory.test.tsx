import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import { LiveRegionProvider } from '@taktovia/design-system';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { BookingHistory, sortiere } from './BookingHistory';
import { basisBuchung, reiseWelt } from './reiseWelt';

const id = (n: number) => `99999999-9999-4999-8999-99999999990${n}`;
const b = (n: number, over: Parameters<typeof basisBuchung>[0] = {}) =>
  basisBuchung({
    id: id(n),
    booking_number: `BK-2026-00000${n}`,
    ...over,
    promise: {
      ...basisBuchung().promise,
      pickup_earliest: `2026-11-2${n}T07:55:00Z`,
      pickup_latest: `2026-11-2${n}T08:10:00Z`,
      ...(over.promise ?? {}),
    },
  });

function welt() {
  // je Bereich mindestens eine Buchung
  return reiseWelt(b(1, { bereich: 'bevorstehend' }), [
    b(2, { bereich: 'laufend', state: 'CONFIRMED' }),
    b(3, { bereich: 'vergangen', state: 'COMPLETED' }),
    b(4, { bereich: 'storniert', state: 'CANCELED' }),
    b(5, { bereich: 'bevorstehend' }),
  ]);
}

function zeige(client: ReturnType<typeof reiseWelt>['client']) {
  return render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <LiveRegionProvider>
          <BookingHistory zone="Europe/Berlin" pollMs={10} refreshMs={40} />
        </LiveRegionProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

const tab = (n: string) => screen.getByTestId(`tab-${n}`);
const nummern = () =>
  within(screen.getByTestId('reisen-panel'))
    .queryAllByRole('listitem')
    .map((li) => li.getAttribute('data-booking-id'));

describe('UI-04 Buchungshistorie in vier Bereichen (WP-M2-45)', () => {
  afterEach(cleanup);

  it('vier_bereiche_mit_anzahl_sortierung_und_leerzustand', async () => {
    const { client } = welt();
    zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    expect(tab('bevorstehend')).toHaveTextContent('Bevorstehend (2)');
    expect(tab('laufend')).toHaveTextContent('Laufend (1)');
    expect(tab('vergangen')).toHaveTextContent('Vergangen (1)');
    expect(tab('storniert')).toHaveTextContent('Storniert (1)');
    // bevorstehend: nächste Abholung zuerst
    expect(nummern()).toEqual([id(1), id(5)]);
    fireEvent.click(tab('storniert'));
    expect(nummern()).toEqual([id(4)]);
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Storniert (1)');
    // vergangen: jüngste Abholung zuerst (reine Sortierfunktion)
    expect(sortiere('vergangen', [b(1), b(5), b(3)]).map((x) => x.id)).toEqual([
      id(5),
      id(3),
      id(1),
    ]);
    expect(sortiere('bevorstehend', [b(5), b(1), b(3)]).map((x) => x.id)).toEqual([
      id(1),
      id(3),
      id(5),
    ]);
  });

  it('leerzustand_je_bereich_und_fehlerzustand_beim_laden', async () => {
    const { client, world } = reiseWelt(b(1, { bereich: 'bevorstehend' }));
    zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    for (const [bereich, text] of [
      ['laufend', 'Aktuell läuft keine Ihrer Reisen.'],
      ['vergangen', 'Sie haben noch keine vergangenen Reisen.'],
      ['storniert', 'Sie haben keine stornierten Reisen.'],
    ] as const) {
      fireEvent.click(tab(bereich));
      expect(screen.getByTestId('reisen-leer')).toHaveTextContent(text);
      expect(tab(bereich)).toHaveTextContent('(0)');
    }
    cleanup();
    world.failNext.set('listBookings', new ApiError(500));
    zeige(client);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });

  it('statuswechsel_verschiebt_ohne_neuladen', async () => {
    const { client, world } = welt();
    zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    expect(tab('storniert')).toHaveTextContent('(1)');
    const reads = world.count('listBookings');
    // Storno auf dem Server (bestätigt -> storniert): der Plan verliert die Buchung, der Strom meldet es
    world.setze(id(1), { state: 'CANCELED', bereich: 'storniert' }, true);
    await waitFor(() => expect(tab('storniert')).toHaveTextContent('Storniert (2)'));
    expect(tab('bevorstehend')).toHaveTextContent('Bevorstehend (1)');
    expect(nummern()).toEqual([id(5)]);
    expect(world.count('listBookings')).toBeGreaterThan(reads);
    // laufend -> vergangen (Testereignis Fahrtende, ohne Planänderung): die regelmäßige Aktualisierung verschiebt
    world.setze(id(2), { state: 'COMPLETED', bereich: 'vergangen' });
    await waitFor(() => expect(tab('vergangen')).toHaveTextContent('Vergangen (2)'));
    expect(tab('laufend')).toHaveTextContent('Laufend (0)');
    // bestätigt -> laufend (Testereignis Fahrtbeginn)
    world.setze(id(5), { bereich: 'laufend' });
    await waitFor(() => expect(tab('laufend')).toHaveTextContent('Laufend (1)'));
    expect(tab('bevorstehend')).toHaveTextContent('Bevorstehend (0)');
  });

  it('verschiebung_wird_zurueckhaltend_angesagt', async () => {
    const { client, world } = welt();
    zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    world.setze(id(1), { state: 'CANCELED', bereich: 'storniert' }, true);
    const live = document.querySelector('[role="status"][aria-live="polite"]');
    await waitFor(() => expect(live).toHaveTextContent('BK-2026-000001 verschoben nach Storniert'));
  });

  it('api_und_ui_gleiche_einteilung', async () => {
    // Der Server entscheidet: ein bestätigter Zustand mit Bereich „vergangen“ (abgelaufen) steht unter „vergangen“, die Oberfläche rechnet nie selbst
    const { client, world } = reiseWelt(b(1, { bereich: 'vergangen', state: 'CONFIRMED' }), [
      b(2, { bereich: 'bevorstehend', state: 'CANCELED' }),
    ]);
    zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    for (const bereich of ['bevorstehend', 'laufend', 'vergangen', 'storniert'] as const) {
      fireEvent.click(tab(bereich));
      const server = world
        .alle()
        .filter((x) => x.bereich === bereich)
        .map((x) => x.id);
      expect(nummern()).toEqual(server);
    }
    // jede Buchung liegt in genau einem Reiter: Summe der Anzahlen = Zahl der Buchungen
    const summe = ['bevorstehend', 'laufend', 'vergangen', 'storniert']
      .map((n) => Number(tab(n).getAttribute('data-count')))
      .reduce((a, c) => a + c, 0);
    expect(summe).toBe(world.alle().length);
    // die Oberfläche liest jeden Bereich mit dem Serverfilter
    const filter = world.calls
      .filter((c) => c.id === 'listBookings')
      .map((c) => (c.args as { query: { bereich: string } }).query.bereich);
    expect(new Set(filter)).toEqual(new Set(['bevorstehend', 'laufend', 'vergangen', 'storniert']));
  });

  it('reiter_per_tastatur_und_ohne_verstoesse_bedienbar', async () => {
    const { client } = welt();
    const { container } = zeige(client);
    await screen.findByTestId('reisen-uebersicht');
    const erster = tab('bevorstehend');
    erster.focus();
    fireEvent.keyDown(erster, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('laufend'));
    expect(tab('laufend')).toHaveAttribute('aria-selected', 'true');
    expect(tab('bevorstehend')).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(tab('laufend'), { key: 'End' });
    expect(document.activeElement).toBe(tab('storniert'));
    fireEvent.keyDown(tab('storniert'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('bevorstehend'));
    expect(screen.getByRole('tablist')).toHaveAccessibleName('Bereiche Ihrer Reisen');
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
