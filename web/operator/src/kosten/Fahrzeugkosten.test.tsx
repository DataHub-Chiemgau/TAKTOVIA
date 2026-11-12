import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  OperationId,
  VehicleCostItem,
  VehicleTotalCost,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { Fahrzeugkosten, monthsBetween } from './Fahrzeugkosten';

const VEHICLE = '00000000-0000-4000-8000-0000000000b1';
const ENTITY = '00000000-0000-4000-8000-0000000000a1';

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };

/** Modell des Servers für die Maske (kein Ersatz für die echten Backend-Tests; ein Browser-E2E ist hier NICHT ausgeführt). */
function setup(scopes: string[], handlers: Handlers) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ subject: 'finanz-1', roles: ['finance'], scopes }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <Fahrzeugkosten />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const item = (month: string, category: Group, label: string, amount: number): VehicleCostItem => ({
  month,
  category,
  cost_group: category,
  label,
  amount_minor: amount,
  event_count: 1,
  by_service_period_events: 0,
  by_accounting_date_events: 1,
});

type Group = VehicleCostItem['cost_group'] & string;

/** Alle sechs Gruppen über drei Monate; Beträge sind je Monat und Kostenart verschieden. */
const GROUPS = [
  ['ACQUISITION_LEASE', 'Anschaffung/Leasing'],
  ['INSURANCE', 'Versicherung'],
  ['VEHICLE_TAX', 'Kfz-Steuer'],
  ['ENERGY', 'Energie'],
  ['MAINTENANCE', 'Wartung'],
  ['REPAIR', 'Reparatur'],
] as const;
const MONTHS = ['2026-09', '2026-10', '2026-11'];
const FULL: VehicleTotalCost = (() => {
  const items = MONTHS.flatMap((m, mi) =>
    GROUPS.map(([c, l], gi) => item(m, c, l, 10000 * (gi + 1) + 100 * (mi + 1))),
  );
  const sum = (f: (i: (typeof items)[number]) => boolean) =>
    items.filter(f).reduce((a, i) => a + i.amount_minor, 0);
  return {
    vehicle_id: VEHICLE,
    valuation: 'ACTUAL',
    currency: 'EUR',
    month_from: '2026-09',
    month_to: '2026-11',
    assignment_rule: 'service_from (Leistungsbeginn, UTC), sonst accounting_date (Buchungsdatum)',
    as_of: '2026-11-12T08:00:00Z',
    total_minor: sum(() => true),
    items,
    month_totals: MONTHS.map((m) => ({ month: m, amount_minor: sum((i) => i.month === m) })),
    category_totals: GROUPS.map(([c]) => ({
      category: c,
      cost_group: c,
      amount_minor: sum((i) => i.category === c),
    })),
  };
})();

const READ = ['costs:read'];

function fill(from = '2026-09', to = '2026-11') {
  fireEvent.change(screen.getByLabelText('Fahrzeug (Kennung)'), { target: { value: VEHICLE } });
  fireEvent.change(screen.getByLabelText('Erster Monat (JJJJ-MM)'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Letzter Monat (JJJJ-MM)'), { target: { value: to } });
  fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
}

describe('UI-15 Fahrzeugkosten (WP-M4-40)', () => {
  it('monatsmatrix_kostenart_mal_monat_leerzustand_und_nur_lesen_ohne_recht', async () => {
    const { calls, container } = setup(READ, { getVehicleTotalCost: () => FULL });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    // Monatsmatrix: Zeilen = Kostenart, Spalten = Monat, centgenau, mit Summen
    const table = await screen.findByRole('table', { name: 'Kosten je Monat und Kostenart' });
    expect(calls.find((c) => c.id === 'getVehicleTotalCost')?.args).toMatchObject({
      path: { vehicle_id: VEHICLE },
      query: { valuation: 'ACTUAL', currency: 'EUR', month_from: '2026-09', month_to: '2026-11' },
    });
    for (const m of ['09/2026', '10/2026', '11/2026'])
      expect(within(table).getByRole('columnheader', { name: m })).toBeInTheDocument();
    for (const [, label] of GROUPS)
      expect(within(table).getByRole('rowheader', { name: label })).toBeInTheDocument();
    const versicherung = within(table).getByRole('row', { name: /^Versicherung/ });
    expect(within(versicherung).getByText('201,00 EUR')).toBeInTheDocument(); // September
    expect(within(versicherung).getByText('202,00 EUR')).toBeInTheDocument(); // Oktober
    expect(within(versicherung).getByText('203,00 EUR')).toBeInTheDocument(); // November
    expect(within(versicherung).getByText('606,00 EUR')).toBeInTheDocument(); // Summe der Kostenart
    expect(
      within(within(table).getByRole('row', { name: /^Summe/ })).getByText('6.336,00 EUR'),
    ).toBeInTheDocument(); // alles
    // Bewertungsart, Währung, Datenstand und Zuordnungsregel stehen im Ergebnis
    expect(
      screen.getByText(/Bewertungsart ACTUAL · Währung EUR · Datenstand 2026-11-12T08:00:00Z/),
    ).toBeInTheDocument();
    expect(screen.getByText(/Monatszuordnung: service_from/)).toBeInTheDocument();
    // ohne das Recht costs:write: nur Lesen, kein Formular
    expect(
      screen.getByText(/Nur Lesen: für das Erfassen manueller Kostenereignisse fehlt das Recht/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Kostenereignis erfassen' })).toBeNull();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('monatsmatrix_leerzustand_fehler_und_ungueltige_eingabe', async () => {
    let answer: unknown = {
      ...FULL,
      items: [],
      month_totals: [],
      category_totals: [],
      total_minor: 0,
    };
    setup(READ, { getVehicleTotalCost: () => answer });
    await screen.findByRole('button', { name: 'Auswerten' });
    // ungültige Eingabe: „Auswerten“ bleibt gesperrt (kein Aufruf)
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    fill('2026-11', '2026-09');
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    // Leerzustand: ein Monat ohne Kosten ist Datenlücke, kein Fehler
    fill('2026-12', '2026-12');
    expect(await screen.findByText('Keine Kosten im gewählten Zeitraum')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Kosten je Monat und Kostenart' })).toBeNull();
    // Fehlerzustand mit erneutem Versuch (fremdes Fahrzeug: 404)
    answer = new ApiError(404);
    fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Kosten je Monat und Kostenart' })).toBeNull();
  });

  it('kostenereignis_erfassen_mit_recht_ruft_den_freigabepflichtigen_befehl_auf', async () => {
    const created = vi.fn(() => ({ state: 'IN_REVIEW', revision: 1 }));
    const { calls } = setup(['costs:read', 'costs:write'], {
      getVehicleTotalCost: () => ({
        ...FULL,
        items: [],
        month_totals: [],
        category_totals: [],
        total_minor: 0,
      }),
      createManualCostEvent: created,
    });
    await screen.findByRole('button', { name: 'Auswerten' });
    fill('2026-09', '2026-09');
    await screen.findByText('Keine Kosten im gewählten Zeitraum');
    const button = await screen.findByRole('button', { name: 'Kostenereignis erfassen' });
    expect(button).toBeDisabled(); // Pflichtfelder fehlen
    fireEvent.change(screen.getByLabelText('Rechtsträger (Kennung)'), {
      target: { value: ENTITY },
    });
    fireEvent.change(screen.getByLabelText('Kostenart'), { target: { value: 'VEHICLE_TAX' } });
    fireEvent.change(screen.getByLabelText('Bruttobetrag in EUR'), { target: { value: '280,00' } });
    fireEvent.change(screen.getByLabelText('Buchungsdatum'), { target: { value: '2026-09-15' } });
    fireEvent.change(screen.getByLabelText('Ursprungsschlüssel'), {
      target: { value: 'kfz-steuer-2026' },
    });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    await waitFor(() => expect(created).toHaveBeenCalledTimes(1));
    expect(calls.find((c) => c.id === 'createManualCostEvent')?.args).toMatchObject({
      body: {
        legal_entity_id: ENTITY,
        vehicle_id: VEHICLE,
        category: 'VEHICLE_TAX',
        gross: { amount_minor: 28000, currency: 'EUR', exponent: 2 },
        accounting_date: '2026-09-15',
        origin_key: 'kfz-steuer-2026',
      },
    });
    expect(
      await screen.findByText(/zur Prüfung eingereicht \(Status IN_REVIEW\)/),
    ).toBeInTheDocument();
  });

  it('monate_zwischen_zaehlt_inklusive_und_lehnt_ungueltige_bereiche_ab', () => {
    expect(monthsBetween('2026-11', '2027-02')).toEqual([
      '2026-11',
      '2026-12',
      '2027-01',
      '2027-02',
    ]);
    expect(monthsBetween('2026-09', '2026-09')).toEqual(['2026-09']);
    expect(monthsBetween('2026-11', '2026-09')).toEqual([]);
    expect(monthsBetween('2026-13', '2026-14')).toEqual([]);
  });
});
