import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { runAxe } from '@taktovia/design-system/testing';
import { LESEN, zeigeDienstplan } from './testrahmen';
import { IDS, W1, W2, baueTestwelt } from './testwelt';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-12-02T10:00:00Z')); // Mittwoch, KW 49 (Montag 2026-11-30)
});
afterEach(() => vi.useRealTimers());

const spaltenkoepfe = () =>
  screen.getAllByRole('columnheader').filter((h) => h.className.includes('dp-tag'));

describe('UI-12 Planer-Dashboard (WP-M4-18, LH-6.23-SCH-001)', () => {
  it('zeigt_zwei_wochen_eines_depots_mit_mitarbeitern_als_zeilen', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt);
    await screen.findByRole('table', { name: /Dienstplan Woche/ });
    fireEvent.change(screen.getByLabelText('Wochen'), { target: { value: '2' } });
    await waitFor(() => expect(spaltenkoepfe()).toHaveLength(14));
    const list = welt.calls.filter((c) => c.id === 'listRosters').at(-1);
    expect(list?.args['query']).toMatchObject({ depot_id: IDS.depotNord, from: W1, to: W2 });
    // Mitarbeiter sind Zeilen, Schichten beider Wochen Einträge
    for (const name of ['Anna Beispiel', 'Ben Muster', 'Cem Probe'])
      expect(screen.getByRole('rowheader', { name: new RegExp(name) })).toBeTruthy();
    const ben = screen.getByRole('rowheader', { name: /Ben Muster/ }).closest('tr') as HTMLElement;
    expect(within(ben).getAllByText(/06:00–14:00|14:00–22:00/).length).toBeGreaterThanOrEqual(2); // 1. und 2. Woche
    expect(screen.getByText(/KW 49/)).toBeTruthy();
    expect(screen.getByText(/KW 50/)).toBeTruthy();
    // Mandant, Depot, Revisionsstatus und Datenstand sind sichtbar
    expect(screen.getByText('Verkehrsbetrieb Nord')).toBeTruthy();
    expect(screen.getByText('Depot Nord', { selector: 'dd' })).toBeTruthy();
    expect(screen.getAllByText(/Entwurf, Revision 1/).length).toBeGreaterThan(0);
    expect(screen.getByText('Datenstand (Server)')).toBeTruthy();
  });

  it('tages_wochen_monatsansicht_zeigen_schichten_als_eintraege', async () => {
    zeigeDienstplan(baueTestwelt());
    await screen.findByRole('table', { name: /Dienstplan Woche/ });
    expect(spaltenkoepfe()).toHaveLength(7);
    expect(screen.getAllByText(/06:00–14:00/).length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('Ansicht'), { target: { value: 'tag' } });
    await screen.findByRole('table', { name: /Dienstplan Tag/ });
    expect(spaltenkoepfe()).toHaveLength(1); // 2026-12-02: Tag ohne Schicht in der Testwelt, Zeilen bleiben
    expect(screen.getByRole('rowheader', { name: /Anna Beispiel/ })).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Ansicht'), { target: { value: 'monat' } });
    await screen.findByRole('table', { name: /Dienstplan Monat/ });
    expect(spaltenkoepfe()).toHaveLength(31); // Dezember
    const anna = screen
      .getByRole('rowheader', { name: /Anna Beispiel/ })
      .closest('tr') as HTMLElement;
    expect(within(anna).getByText(/06:00–14:00/)).toBeTruthy();
    expect(within(anna).getByText(/Bus 12/)).toBeTruthy(); // zugeordnetes Fahrzeug je Schicht
  });

  it('offene_schichten_hervorgehoben_und_gezaehlt', async () => {
    const { container } = zeigeDienstplan(baueTestwelt());
    await screen.findByRole('table', { name: /Dienstplan Woche/ });
    const offen = screen.getByRole('rowheader', { name: /Offene Dienste/ });
    expect(within(offen).getByText(/2 offen/)).toBeTruthy(); // Zähler
    const zeile = offen.closest('tr') as HTMLElement;
    // Status nie nur Farbe: Text „Offen“ und Symbol je offener Schicht
    expect(within(zeile).getAllByText('Offen')).toHaveLength(2);
    expect(zeile.querySelectorAll('[data-offen="true"]')).toHaveLength(2);
    expect(screen.getByText(/2 offen, 2 Personalbedarf ungedeckt/)).toBeTruthy();
    // Konflikt-/Hinweismarken: unbesetzter Dienst, Abwesenheit als „nicht verfügbar“
    expect(screen.getAllByText('nicht verfügbar')).toHaveLength(2); // 3. und 4. Dezember
    expect(await runAxe(container)).toEqual([]);
  });

  it('tabellenalternative_per_tastatur_nutzbar', async () => {
    const { container } = zeigeDienstplan(baueTestwelt());
    await screen.findByRole('table', { name: /Dienstplan Woche/ });
    const schalter = screen.getByRole('button', { name: 'Tabellenalternative anzeigen' });
    schalter.focus();
    expect(document.activeElement).toBe(schalter); // erreichbar per Tab (native Schaltfläche)
    fireEvent.click(schalter); // Enter/Leertaste lösen bei nativen Schaltflächen click aus
    const tabelle = await screen.findByRole('table', { name: /Tabellenalternative/ });
    expect(schalter.getAttribute('aria-pressed')).toBe('true');
    const zeilen = within(tabelle).getAllByRole('row');
    expect(zeilen).toHaveLength(1 + 4); // Kopf + 4 Schichten der Woche
    expect(
      within(tabelle)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(expect.arrayContaining(['Tag', 'Zeit', 'Mitarbeiter', 'Fahrzeug', 'Status']));
    expect(within(tabelle).getAllByText('Offen')).toHaveLength(2);
    expect(await runAxe(container)).toEqual([]);
  });

  it('zustaende_leer_fehler_keine_rechte', async () => {
    // leer: kein Dienstplan im Zeitraum
    const leer = baueTestwelt();
    const handle = leer.handle;
    leer.handle = (id, args) =>
      id === 'listRosters'
        ? { items: [], next_cursor: null, as_of: '2026-12-02T09:00:00Z' }
        : handle(id, args);
    const a = zeigeDienstplan(leer);
    expect(
      await screen.findByRole('heading', { name: 'Kein Dienstplan im Zeitraum' }),
    ).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    a.unmount();

    // Fehler: Ladefehler mit Wiederholen
    const kaputt = baueTestwelt();
    kaputt.scheitern('listRosters', 503);
    const b = zeigeDienstplan(kaputt);
    const retry = await screen.findByRole('button', { name: 'Erneut versuchen' });
    expect(screen.getByRole('alert')).toBeTruthy();
    fireEvent.click(retry);
    expect(await screen.findByRole('table', { name: /Dienstplan Woche/ })).toBeTruthy();
    b.unmount();

    // keine Rechte: ohne Funktionsscope kein Bereich, kein Datenaufruf
    const ohne = baueTestwelt();
    const c = zeigeDienstplan(ohne, ['me:read']);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(ohne.zaehle('listRosters')).toBe(0);
    c.unmount();

    // Server verweigert (403): keine Daten, Rechtezustand
    const verboten = baueTestwelt();
    verboten.scheitern('getRoster', 403);
    zeigeDienstplan(verboten, LESEN);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('Anna Beispiel')).toBeNull();
  });

  it('fremdes_depot_nicht_auswaehlbar', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt);
    await screen.findByRole('table', { name: /Dienstplan Woche/ });
    const auswahl = screen.getByLabelText('Depot');
    const optionen = within(auswahl)
      .getAllByRole('option')
      .map((o) => (o as HTMLOptionElement).value);
    expect(optionen).toEqual([IDS.depotNord, IDS.depotSued]); // nur Depots des Mandanten
    expect(optionen).not.toContain(IDS.fremdesDepot);
    // Ein fremdes Depot liefert serverseitig 404: Zustand „nicht gefunden“ ohne Daten
    const handle = welt.handle;
    welt.handle = (id, args) => {
      if (
        id === 'listRosters' &&
        (args['query'] as Record<string, string>)['depot_id'] === IDS.depotSued
      )
        return handle(id, { query: { depot_id: IDS.fremdesDepot } });
      return handle(id, args);
    };
    fireEvent.change(auswahl, { target: { value: IDS.depotSued } });
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
