import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { tagKurz } from './model';
import { LESEN, SCHREIBEN, VOLL, zeigeDienstplan } from './testrahmen';
import { IDS, TAG, baueTestwelt, uuid } from './testwelt';
import type { Testwelt } from './testwelt';

const SCHICHT_OFFEN_ROLLSTUHL = uuid(0x503);
const SCHICHT_OFFEN = uuid(0x504);
const SCHICHT_ANNA = uuid(0x501);

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-12-02T10:00:00Z'));
});
afterEach(() => vi.useRealTimers());

const dataTransfer = () => {
  const m = new Map<string, string>();
  return {
    setData: (k: string, v: string) => m.set(k, v),
    getData: (k: string) => m.get(k) ?? '',
    effectAllowed: '',
  };
};

async function geladen() {
  await screen.findByRole('table', { name: /Dienstplan Woche/ });
}
const eintrag = (id: string): HTMLElement => {
  const el = document.querySelector<HTMLElement>(`[data-shift-id="${id}"]`);
  if (!el) throw new Error(`Schicht ${id} nicht im Raster`);
  return el;
};
/** Zelle (Person, Tag) im Raster: Spaltenposition über die Tageskopfzeile. */
function zelle(person: string | RegExp, day: string): HTMLElement {
  const koepfe = screen.getAllByRole('columnheader').filter((h) => h.className.includes('dp-tag'));
  const index = koepfe.findIndex((h) => h.textContent === tagKurz(day));
  const zeile = screen.getByRole('rowheader', { name: person }).closest('tr') as HTMLElement;
  return within(zeile).getAllByRole('cell')[index] as HTMLElement;
}
/** Zeitangaben der Schichteinträge einer Zelle (ohne Meldungstexte). */
const zeiten = (z: HTMLElement): string[] =>
  [...z.querySelectorAll('.dp-zeit')].map((e) => e.textContent ?? '');
const ziehe = (id: string, ziel: HTMLElement) => {
  const dt = dataTransfer();
  fireEvent.dragStart(eintrag(id), { dataTransfer: dt });
  fireEvent.dragOver(ziel, { dataTransfer: dt });
  fireEvent.drop(ziel, { dataTransfer: dt });
};
const updates = (welt: Testwelt) => welt.calls.filter((c) => c.id === 'updateRosterShift');
const dialog = () => screen.findByRole('dialog');

describe('UI-12 Schichten per Klick, Kopie und Drag-and-drop (WP-M4-19, LH-6.23-SCH-003)', () => {
  it('drag_and_drop_speichert_ohne_seitenwechsel', async () => {
    const welt = baueTestwelt();
    const view = zeigeDienstplan(welt, SCHREIBEN);
    await geladen();
    const tabelle = screen.getByRole('table', { name: /Dienstplan Woche/ });
    expect(eintrag(SCHICHT_OFFEN_ROLLSTUHL).getAttribute('draggable')).toBe('true');

    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));

    await waitFor(() => expect(updates(welt)).toHaveLength(1));
    const call = updates(welt)[0];
    expect(call?.args['revision']).toBe(1); // If-Match mit der gelesenen Revision
    expect(call?.args['body']).toMatchObject({
      driver_ref: IDS.anna,
      starts_local: `${TAG(2)}T06:00:00`,
      roster_id: IDS.rosterW1,
    });
    // ohne vollständigen Seitenwechsel: dieselbe Tabelle bleibt im DOM, Meldung über die Live-Region
    await waitFor(() => expect(zeiten(zelle(/Anna Beispiel/, TAG(2)))).toContain('06:00–14:00'));
    expect(screen.getByRole('table', { name: /Dienstplan Woche/ })).toBe(tabelle);
    const live = view.container.querySelector('.dp-live');
    await waitFor(() => expect(live?.textContent).toMatch(/Gespeichert/));
    expect(
      welt.entwurf.get(IDS.rosterW1)?.find((s) => s.id === SCHICHT_OFFEN_ROLLSTUHL)?.driver_ref,
    ).toBe(IDS.anna);

    // nach Neuladen vorhanden (frische Anwendung gegen denselben Serverstand)
    view.unmount();
    zeigeDienstplan(welt, SCHREIBEN);
    await geladen();
    expect(zeiten(zelle(/Anna Beispiel/, TAG(2)))).toContain('06:00–14:00');
    expect(screen.getByRole('rowheader', { name: /Offene Dienste/ }).textContent).toMatch(
      /1 offen/,
    );
  });

  it('anlegen_per_klick_und_kopie_erzeugt_entwurf', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt, SCHREIBEN);
    await geladen();

    // Anlegen per Klick in eine Rasterzelle
    fireEvent.click(
      screen.getByRole('button', { name: `Schicht anlegen für Ben Muster am ${tagKurz(TAG(3))}` }),
    );
    const d = await dialog();
    expect(within(d).getByRole('heading', { name: 'Schicht anlegen' })).toBeTruthy();
    fireEvent.change(within(d).getByLabelText('Beginn (lokale Zeit)'), {
      target: { value: '08:00' },
    });
    fireEvent.change(
      within(d).getByLabelText('Ende (lokale Zeit, am Folgetag bei früherer Uhrzeit)'),
      { target: { value: '16:00' } },
    );
    fireEvent.click(within(d).getByRole('button', { name: 'Speichern' }));
    await waitFor(() => expect(welt.zaehle('createRosterShift')).toBe(1));
    const neu = welt.calls.find((c) => c.id === 'createRosterShift')?.args['body'];
    expect(neu).toMatchObject({
      roster_id: IDS.rosterW1,
      depot_id: IDS.depotNord,
      operator_id: IDS.operator,
      driver_ref: IDS.ben,
      starts_local: `${TAG(3)}T08:00`,
      ends_local: `${TAG(3)}T16:00`,
    });
    await waitFor(() => expect(zeiten(zelle(/Ben Muster/, TAG(3)))).toContain('08:00–16:00'));
    expect(welt.entwurf.get(IDS.rosterW1)?.every((s) => s.state === 'DRAFT')).toBe(true); // Entwurf

    // Kopieren einer Schicht: Anna, 1. Dezember -> Ben, 4. Dezember
    fireEvent.click(
      screen.getByRole('button', {
        name: new RegExp(`Kopieren, Schicht 06:00–14:00 am ${tagKurz(TAG(1))}, Anna Beispiel`),
      }),
    );
    const k = await dialog();
    expect(within(k).getByRole('heading', { name: 'Schicht kopieren' })).toBeTruthy();
    fireEvent.change(within(k).getByLabelText('Tag (Betriebstag)'), { target: { value: TAG(4) } });
    fireEvent.change(within(k).getByLabelText('Person'), { target: { value: IDS.ben } });
    fireEvent.click(within(k).getByRole('button', { name: 'Speichern' }));
    await waitFor(() => expect(welt.zaehle('createRosterShift')).toBe(2));
    expect(welt.calls.filter((c) => c.id === 'createRosterShift')[1]?.args['body']).toMatchObject({
      driver_ref: IDS.ben,
      starts_local: `${TAG(4)}T06:00`,
      function: 'DRIVER',
    });
    await waitFor(() => expect(zeiten(zelle(/Ben Muster/, TAG(4)))).toContain('06:00–14:00'));
    expect(zeiten(zelle(/Anna Beispiel/, TAG(1)))).toContain('06:00–14:00'); // Original bleibt
  });

  it('geeignete_personen_werden_angeboten', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt, SCHREIBEN);
    await geladen();
    // Schicht mit verlangter Qualifikation ROLLSTUHL: nur Anna ist geeignet
    fireEvent.click(
      within(eintrag(SCHICHT_OFFEN_ROLLSTUHL)).getByRole('button', {
        name: /Verschieben\/Zuweisen/,
      }),
    );
    const d = await dialog();
    const gruppe = (name: string) => within(d).getByRole('group', { name });
    await waitFor(() =>
      expect(
        within(gruppe('Geeignete Personen'))
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['Anna Beispiel']),
    );
    const ungeeignet = within(gruppe('Nicht geeignet'))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(ungeeignet).toEqual([
      'Ben Muster (nicht geeignet: Qualifikation ROLLSTUHL fehlt oder nicht gültig)',
      'Cem Probe (nicht geeignet: Qualifikation ROLLSTUHL fehlt oder nicht gültig)',
    ]);
    expect(await runAxe(d)).toEqual([]);
    fireEvent.keyDown(d, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // Schicht ohne Qualifikation am 3. Dezember: Cem ist abwesend und deshalb nicht geeignet
    fireEvent.click(
      within(eintrag(SCHICHT_OFFEN)).getByRole('button', { name: /Verschieben\/Zuweisen/ }),
    );
    const e = await dialog();
    await waitFor(() =>
      expect(
        within(within(e).getByRole('group', { name: 'Geeignete Personen' })).getAllByRole('option'),
      ).toHaveLength(2),
    );
    expect(
      within(within(e).getByRole('group', { name: 'Nicht geeignet' })).getByRole('option')
        .textContent,
    ).toBe('Cem Probe (nicht geeignet: Abwesenheit am Betriebstag)');
  });

  it('unqualifizierte_zuordnung_zeigt_konfliktursache', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt, VOLL);
    await geladen();
    fireEvent.click(
      within(eintrag(SCHICHT_OFFEN_ROLLSTUHL)).getByRole('button', {
        name: /Verschieben\/Zuweisen/,
      }),
    );
    const d = await dialog();
    await waitFor(() =>
      expect(
        within(within(d).getByRole('group', { name: 'Geeignete Personen' })).getAllByRole('option'),
      ).toHaveLength(1),
    );
    fireEvent.change(within(d).getByLabelText('Person'), { target: { value: IDS.ben } });
    expect(within(d).getByText(/Ungeeignet/)).toBeTruthy(); // Vorwarnung im Dialog
    fireEvent.click(within(d).getByRole('button', { name: 'Speichern' }));

    // Konfliktursache direkt an der Schicht, in Text und Symbol
    await waitFor(() =>
      expect(
        within(eintrag(SCHICHT_OFFEN_ROLLSTUHL)).getByText(/Konflikt: Qualifikation fehlt/),
      ).toBeTruthy(),
    );
    const an = eintrag(SCHICHT_OFFEN_ROLLSTUHL);
    expect(within(an).getByText(/Fehlend: ROLLSTUHL/)).toBeTruthy();
    expect(an.getAttribute('data-konflikt')).toBe('true');
    // blockierender Konflikt sperrt das Veröffentlichen
    const sperre = screen.getAllByRole('button', {
      name: 'Entwurf veröffentlichen',
    })[0] as HTMLButtonElement;
    expect(sperre.disabled).toBe(true);
    expect(screen.getByText(/1 blockierende Konflikte/)).toBeTruthy();
  });

  it('tastaturpfad_entspricht_drag_and_drop', async () => {
    // Pfad 1: Drag-and-drop
    const a = baueTestwelt();
    const va = zeigeDienstplan(a, SCHREIBEN);
    await geladen();
    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));
    await waitFor(() => expect(updates(a)).toHaveLength(1));
    va.unmount();

    // Pfad 2: nur Tastatur (Schaltflächen und Formularsteuerung, kein Drag-Ereignis)
    const b = baueTestwelt();
    zeigeDienstplan(b, SCHREIBEN);
    await geladen();
    const knopf = within(eintrag(SCHICHT_OFFEN_ROLLSTUHL)).getByRole('button', {
      name: /Verschieben\/Zuweisen/,
    });
    knopf.focus();
    expect(document.activeElement).toBe(knopf);
    fireEvent.click(knopf);
    const d = await dialog();
    expect(document.activeElement).toBe(within(d).getByLabelText('Person')); // Fokus im Dialog
    await waitFor(() =>
      expect(
        within(within(d).getByRole('group', { name: 'Geeignete Personen' })).getAllByRole('option'),
      ).toHaveLength(1),
    );
    fireEvent.change(within(d).getByLabelText('Person'), { target: { value: IDS.anna } });
    fireEvent.change(within(d).getByLabelText('Tag (Betriebstag)'), { target: { value: TAG(2) } });
    fireEvent.click(within(d).getByRole('button', { name: 'Speichern' }));
    await waitFor(() => expect(updates(b)).toHaveLength(1));
    // gleicher Aufruf wie beim Ziehen: gleiche Operation, Revision und Schreibmodell
    expect(updates(b)[0]?.args['body']).toEqual(updates(a)[0]?.args['body']);
    expect(updates(b)[0]?.args['revision']).toBe(updates(a)[0]?.args['revision']);
    // Abbruch per Escape gibt den Fokus zurück
    fireEvent.click(within(eintrag(SCHICHT_ANNA)).getByRole('button', { name: /^Kopieren/ }));
    const k = await dialog();
    fireEvent.keyDown(k, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('veralteter_stand_zeigt_konfliktdialog', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt, SCHREIBEN);
    await geladen();
    welt.fremdeAenderung(SCHICHT_OFFEN_ROLLSTUHL); // zweiter Planer war schneller (Revision 2)
    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));

    const konflikt = await screen.findByRole('alertdialog');
    expect(updates(welt)).toHaveLength(1);
    // kein stilles Überschreiben: es gibt keine Überschreiben-Aktion, der Stand des anderen bleibt
    expect(within(konflikt).queryByRole('button', { name: /überschreiben/i })).toBeNull();
    expect(
      welt.entwurf.get(IDS.rosterW1)?.find((s) => s.id === SCHICHT_OFFEN_ROLLSTUHL)?.driver_ref,
    ).toBeNull();
    expect(await runAxe(konflikt)).toEqual([]);
    fireEvent.click(within(konflikt).getByRole('button', { name: /prüfen/i }));
    expect(await screen.findByText(/Ihre nicht gespeicherte Änderung/)).toBeTruthy();
    // aktuellen Stand laden: danach erneutes Ziehen trägt die neue Revision und gelingt
    const listen = welt.zaehle('listRosterShifts');
    fireEvent.click(within(konflikt).getByRole('button', { name: /laden/i }));
    await waitFor(() => expect(welt.zaehle('listRosterShifts')).toBeGreaterThan(listen));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));
    await waitFor(() => expect(updates(welt)).toHaveLength(2));
    expect(updates(welt)[1]?.args['revision']).toBe(2);
    await waitFor(() =>
      expect(
        welt.entwurf.get(IDS.rosterW1)?.find((s) => s.id === SCHICHT_OFFEN_ROLLSTUHL)?.driver_ref,
      ).toBe(IDS.anna),
    );
  });

  it('fehlendes_recht_verhindert_aktion', async () => {
    // ohne Schreibscope: keine Bearbeitungselemente, nichts ist ziehbar, kein Veröffentlichen
    const lesen = baueTestwelt();
    const v = zeigeDienstplan(lesen, LESEN);
    await geladen();
    expect(screen.queryByRole('button', { name: /Schicht anlegen für/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Verschieben\/Zuweisen/ })).toBeNull();
    expect(eintrag(SCHICHT_OFFEN_ROLLSTUHL).getAttribute('draggable')).toBeNull();
    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));
    expect(lesen.zaehle('updateRosterShift')).toBe(0);
    expect(screen.queryByRole('button', { name: 'Entwurf veröffentlichen' })).toBeNull();
    v.unmount();

    // Schreibscope in der Sitzung, Server verweigert (403): Meldung an der Schicht, nichts geändert
    const welt = baueTestwelt();
    welt.scheitern('updateRosterShift', 403);
    zeigeDienstplan(welt, SCHREIBEN);
    await geladen();
    ziehe(SCHICHT_OFFEN_ROLLSTUHL, zelle(/Anna Beispiel/, TAG(2)));
    const meldung = await within(eintrag(SCHICHT_OFFEN_ROLLSTUHL)).findByRole('alert');
    expect(meldung.textContent).toMatch(/fehlt das Recht/);
    expect(
      welt.entwurf.get(IDS.rosterW1)?.find((s) => s.id === SCHICHT_OFFEN_ROLLSTUHL)?.driver_ref,
    ).toBeNull();
  });

  it('veroeffentlichen_ueber_die_oberflaeche', async () => {
    const welt = baueTestwelt();
    zeigeDienstplan(welt, VOLL);
    await geladen();
    const kopf = screen.getByRole('region', { name: 'Entwurf veröffentlichen' });
    fireEvent.click(
      within(kopf).getAllByRole('button', { name: 'Entwurf veröffentlichen' })[0] as HTMLElement,
    );
    const d = await dialog();
    expect(
      (within(d).getByRole('button', { name: 'Veröffentlichen' }) as HTMLButtonElement).disabled,
    ).toBe(true); // Pflichtangaben fehlen
    fireEvent.change(within(d).getByLabelText('Begründung'), {
      target: { value: 'Wochenplanung freigegeben' },
    });
    fireEvent.change(within(d).getByLabelText('Wirksam ab'), {
      target: { value: '2026-12-07T00:00:00' },
    });
    fireEvent.change(within(d).getByLabelText('Wirkungsbericht (Kennung)'), {
      target: { value: IDS.rosterW3 },
    });
    fireEvent.click(within(d).getByRole('button', { name: 'Veröffentlichen' }));
    await waitFor(() => expect(welt.zaehle('publishRoster')).toBe(1));
    const call = welt.calls.find((c) => c.id === 'publishRoster');
    expect(call?.args['revision']).toBe(3); // If-Match = Stand des Dienstplans
    expect(call?.args['body']).toMatchObject({
      reason: 'Wochenplanung freigegeben',
      impact_report_id: IDS.rosterW3,
      effective_from: '2026-12-07T00:00:00+01:00',
    });
    expect(await screen.findByText(/KW 49 veröffentlicht \(Revision 2\)/)).toBeTruthy();
    await waitFor(() => expect(welt.entwurf.has(IDS.rosterW1)).toBe(false));
    expect(formatViolations(await runAxe(document.body))).toBe('');
  });
});
