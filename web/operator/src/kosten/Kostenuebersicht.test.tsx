import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  AnalyticsMetrics,
  ApiClient,
  CostClassification,
  CostEvent,
  KmMetrics,
  MetricValue,
  OperationId,
  VehicleTotalCost,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { Kostenuebersicht, lastDay } from './Kostenuebersicht';

const ENTITY = '00000000-0000-4000-8000-0000000000a1';
const FOREIGN_ENTITY = '00000000-0000-4000-8000-0000000000f1';
const VEHICLE = '00000000-0000-4000-8000-0000000000b1';
const OBJECT = '00000000-0000-4000-8000-0000000000c1';

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
        <Kostenuebersicht />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const ZERO = {
  fixed_minor: 0,
  variable_minor: 0,
  mixed_minor: 0,
  unclassified_minor: 0,
  direct_minor: 0,
  allocated_minor: 0,
  total_minor: 0,
};

/** Das Testfahrzeug: fix/direkt 300,00; fix/umgelegt 50,00; variabel/direkt 100,00 (Energie) und variabel/umgelegt 25,50; nicht klassifiziert 12,34. */
const CLASSIFICATION: CostClassification = {
  valuation: 'ACTUAL',
  currency: 'EUR',
  period_from: '2026-09-01',
  period_to: '2026-12-01',
  period_basis: 'ACCOUNTING_DATE',
  as_of: '2026-11-12T08:00:00Z',
  event_count: 7,
  totals: {
    ...ZERO,
    fixed_minor: 35000,
    variable_minor: 12550,
    unclassified_minor: 1234,
    direct_minor: 40000,
    allocated_minor: 7550,
    total_minor: 48784,
  },
  rows: [
    {
      cost_object_id: OBJECT,
      cost_object_kind: 'VEHICLE',
      business_ref: 'M-TV 123',
      vehicle_id: VEHICLE,
      totals: {
        ...ZERO,
        fixed_minor: 35000,
        variable_minor: 12550,
        unclassified_minor: 1234,
        direct_minor: 40000,
        allocated_minor: 7550,
        total_minor: 48784,
      },
      cells: [
        { cost_behavior: 'FIXED', attribution: 'DIRECT', amount_minor: 30000, event_count: 2 },
        { cost_behavior: 'FIXED', attribution: 'ALLOCATED', amount_minor: 5000, event_count: 1 },
        { cost_behavior: 'VARIABLE', attribution: 'DIRECT', amount_minor: 10000, event_count: 2 },
        { cost_behavior: 'VARIABLE', attribution: 'ALLOCATED', amount_minor: 2550, event_count: 1 },
        { cost_behavior: null, attribution: null, amount_minor: 1234, event_count: 1 },
      ],
    },
  ],
};

const MONTHLY: VehicleTotalCost = {
  vehicle_id: VEHICLE,
  valuation: 'ACTUAL',
  currency: 'EUR',
  month_from: '2026-09',
  month_to: '2026-11',
  assignment_rule: 'service_from (Leistungsbeginn, UTC), sonst accounting_date (Buchungsdatum)',
  as_of: '2026-11-12T08:00:00Z',
  total_minor: 40000,
  items: [
    {
      month: '2026-09',
      category: 'INSURANCE',
      cost_group: 'INSURANCE',
      label: 'Versicherung',
      amount_minor: 30000,
      event_count: 1,
      by_service_period_events: 0,
      by_accounting_date_events: 1,
    },
    {
      month: '2026-10',
      category: 'ENERGY',
      cost_group: 'ENERGY',
      label: 'Energie',
      amount_minor: 10000,
      event_count: 1,
      by_service_period_events: 0,
      by_accounting_date_events: 1,
    },
  ],
  month_totals: [
    { month: '2026-09', amount_minor: 30000 },
    { month: '2026-10', amount_minor: 10000 },
  ],
  category_totals: [
    { category: 'INSURANCE', cost_group: 'INSURANCE', amount_minor: 30000 },
    { category: 'ENERGY', cost_group: 'ENERGY', amount_minor: 10000 },
  ],
};

const PERIOD = {
  from: '2026-09-01',
  to: '2026-11-30',
  timezone: 'Europe/Berlin',
  from_instant: '2026-08-31T22:00:00Z',
  to_instant: '2026-11-30T23:00:00Z',
};
const metric = (over: Partial<MetricValue>): MetricValue => ({
  metric_id: 'energy_cost_per_100km',
  definition_version: 1,
  state: 'OK',
  reason: null,
  value: 8108,
  numerator: 15000,
  denominator: 185,
  coverage: 1,
  unit: 'Minor Units je 100 km',
  filter: { energy: 'FUEL', currency: 'EUR', vehicle_id: VEHICLE },
  period: PERIOD,
  as_of: '2026-11-12T08:00:00Z',
  source_events: 5,
  snapshot_id: '00000000-0000-4000-8000-0000000000d1',
  exclusions: [],
  approximation: 'ACQUISITION_BASIS_P1',
  ...over,
});
const NO_ELECTRIC = metric({
  state: 'NOT_COMPUTABLE',
  reason: 'NO_DATA',
  value: null,
  numerator: null,
  denominator: null,
  coverage: null,
  filter: { energy: 'ELECTRIC' },
});
const metrics = (values: MetricValue[]): AnalyticsMetrics => ({
  definitions: [],
  values,
  rejections: null,
  not_performed: null,
  funnel: null,
});

const KM: KmMetrics = {
  metric: 'VEHICLE_KM_CLASSES',
  definition_version: 1,
  from: '2026-09-01',
  to: '2026-11-30',
  vehicle_id: VEHICLE,
  as_of: '2026-11-12T08:00:00Z',
  completeness: 'COMPLETE',
  total_km_thousandths: 185000,
  km_thousandths_by_category: { PASSENGER: 120000, EMPTY: 40000, TRANSFER: 20000, WORKSHOP: 0 },
  unassigned_km_thousandths: 5000,
  days: [
    {
      service_day: '2026-09-01',
      vehicle_id: VEHICLE,
      run_id: '00000000-0000-4000-8000-0000000000e1',
      as_of: '2026-09-02T00:00:00Z',
      total_km_thousandths: 185000,
      km_thousandths_by_category: { PASSENGER: 120000, EMPTY: 40000, TRANSFER: 20000, WORKSHOP: 0 },
      unassigned_km_thousandths: 5000,
      events_complete: true,
    },
  ],
};

const event = (id: string, category: string, cents: number): CostEvent => ({
  id,
  legal_entity_id: ENTITY,
  source_system: 'EXPENSE',
  source_ref: `beleg-${id.slice(-2)}`,
  event_type: 'EXPENSE_COST',
  scenario: 'ACTUAL',
  category,
  currency: 'EUR',
  net_minor: cents,
  tax_minor: 0,
  gross_minor: cents,
  recoverable_tax_minor: 0,
  cost_basis_minor: cents,
  tax_rule_revision: 'T',
  occurred_at: '2026-09-15T00:00:00Z',
  booked_at: '2026-09-15T10:00:00Z',
  document_id: '00000000-0000-4000-8000-0000000000d2',
  reverses_id: null,
});

const READ = ['costs:read', 'analytics:read', 'organization:read'];
const base: Handlers = {
  listLegalEntities: () => ({
    items: [{ id: ENTITY, name: 'Test GmbH', country: 'DE', revision: 1 }],
    next_cursor: null,
    as_of: '2026-11-12T08:00:00Z',
  }),
  getCostClassification: () => CLASSIFICATION,
  getVehicleTotalCost: () => MONTHLY,
  getAnalyticsMetrics: () => metrics([metric({}), NO_ELECTRIC]),
  getVehicleKmClasses: () => KM,
};

async function auswerten(from = '2026-09-01', to = '2026-12-01') {
  await screen.findByRole('option', { name: 'Test GmbH' });
  fireEvent.change(screen.getByLabelText(/Beginn der Periode/), { target: { value: from } });
  fireEvent.change(screen.getByLabelText(/Ende der Periode/), { target: { value: to } });
  fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
}

async function objektOeffnen() {
  const table = await screen.findByRole('table', { name: 'Kostenobjekte' });
  fireEvent.click(within(table).getByRole('button', { name: /Anzeigen/ }));
}

describe('UI-15 Kostenübersicht je Kostenobjekt (WP-M4-44)', () => {
  it('lastDay_ist_der_tag_vor_dem_exklusiven_ende', () => {
    expect(lastDay('2026-12-01')).toBe('2026-11-30');
    expect(lastDay('2026-03-01')).toBe('2026-02-28');
  });

  it('kreuztabelle_vier_zellen_summen_konsistent_zur_api_energie_und_drilldown', async () => {
    const { calls, container } = setup(READ, {
      ...base,
      listCostEvents: (a) => ({
        items: [event('00000000-0000-4000-8000-0000000000d3', 'INSURANCE', 30000)],
        next_cursor: null,
        as_of: String(a.query ? '2026-11-12T08:00:00Z' : ''),
      }),
    });
    await auswerten();
    // Aufruf: Bewertungsart Ist, Währung, Periode und der gewählte Rechtsträger
    await waitFor(() => expect(calls.find((c) => c.id === 'getCostClassification')).toBeTruthy());
    expect(calls.find((c) => c.id === 'getCostClassification')?.args).toMatchObject({
      query: {
        valuation: 'ACTUAL',
        currency: 'EUR',
        period_from: '2026-09-01',
        period_to: '2026-12-01',
        legal_entity_id: ENTITY,
      },
    });
    expect(await screen.findByText(/Bewertungsart ACTUAL · Währung EUR/)).toBeInTheDocument();
    await objektOeffnen();
    // Kreuztabelle: vier Zellen und Summen genau wie in der API
    const kreuz = await screen.findByRole('table', {
      name: 'Kosten nach Kostenverhalten und Zurechnung',
    });
    const fix = within(kreuz).getByRole('row', { name: /^fix/ });
    expect(within(fix).getByText('300,00 EUR')).toBeInTheDocument(); // fix direkt
    expect(within(fix).getByText('50,00 EUR')).toBeInTheDocument(); // fix umgelegt
    expect(within(fix).getByText('350,00 EUR')).toBeInTheDocument(); // Zeilensumme (API fixed_minor)
    const variabel = within(kreuz).getByRole('row', { name: /^variabel/ });
    expect(within(variabel).getByText('100,00 EUR')).toBeInTheDocument();
    expect(within(variabel).getByText('25,50 EUR')).toBeInTheDocument();
    expect(within(variabel).getByText('125,50 EUR')).toBeInTheDocument();
    const gesamt = within(kreuz).getByRole('row', { name: /^Summe\s+400,00/ });
    expect(within(gesamt).getByText('400,00 EUR')).toBeInTheDocument(); // direkt
    expect(within(gesamt).getByText('75,50 EUR')).toBeInTheDocument(); // umgelegt
    expect(within(gesamt).getByText('487,84 EUR')).toBeInTheDocument(); // total inklusive nicht klassifiziert (12,34)
    expect(
      within(within(kreuz).getByRole('row', { name: /^nicht klassifiziert/ })).getAllByText(
        '12,34 EUR',
      ).length,
    ).toBeGreaterThan(0);
    // Monatsauswertung nach Kostenart
    const monate = await screen.findByRole('table', { name: 'Kosten je Monat und Kostenart' });
    expect(
      within(within(monate).getByRole('row', { name: /^Versicherung/ })).getAllByText('300,00 EUR')
        .length,
    ).toBeGreaterThan(0);
    expect(calls.find((c) => c.id === 'getVehicleTotalCost')?.args).toMatchObject({
      path: { vehicle_id: VEHICLE },
      query: { valuation: 'ACTUAL', currency: 'EUR', month_from: '2026-09', month_to: '2026-11' },
    });
    // Energiekosten je 100 km mit Nenner, Näherung und Stand; die andere Energieart ohne Daten ist kein Wert 0
    const energie = await screen.findByRole('region', { name: 'Energiekosten Kraftstoff (Liter)' });
    expect(within(energie).getByText(/81,08 EUR je 100 km/)).toBeInTheDocument();
    expect(within(energie).getByText(/Nenner 185,000 km/)).toBeInTheDocument();
    expect(
      within(await screen.findByRole('region', { name: 'Energiekosten Strom (kWh)' })).getByText(
        /keine plausibilisierten Tankvorgänge/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/P1-Näherung: Erwerbsbasis/)).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'getAnalyticsMetrics')?.args).toMatchObject({
      query: {
        metric_id: 'energy_cost_per_100km',
        from: '2026-09-01',
        to: '2026-11-30',
        vehicle_id: VEHICLE,
        currency: 'EUR',
      },
    });
    // Hinweise: km unzugeordnet, ungeklärte Belege, Datenvollständigkeit
    expect(await screen.findByText(/km unzugeordnet: 5,000 km/)).toBeInTheDocument();
    const hinweise = screen.getByRole('region', { name: 'Hinweise' });
    expect(within(hinweise).getByText(/Datenvollständigkeit/)).toBeInTheDocument();
    expect(within(hinweise).getByText(/Ungeklärte Belege: 12,34 EUR/)).toBeInTheDocument();
    // Drilldown auf die Ursprungsereignisse hinter der Zelle fix/direkt
    fireEvent.click(within(fix).getByRole('button', { name: /Ereignisse\s*fix\s*direkt/ }));
    const ursprung = await screen.findByRole('region', { name: 'Ursprungsereignisse' });
    expect(await within(ursprung).findByText('beleg-d3', { exact: false })).toBeInTheDocument();
    expect(calls.filter((c) => c.id === 'listCostEvents').at(-1)?.args).toMatchObject({
      query: {
        cost_object_id: OBJECT,
        cost_behavior: 'FIXED',
        attribution: 'DIRECT',
        currency: 'EUR',
        period_from: '2026-09-01',
        period_to: '2026-12-01',
        legal_entity_id: ENTITY,
      },
    });
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('nenner_null_datenluecke_und_vorlaeufiger_wert_ohne_scheinwert', async () => {
    let answer: AnalyticsMetrics = metrics([
      metric({
        state: 'NOT_COMPUTABLE',
        reason: 'ZERO_DENOMINATOR',
        value: null,
        denominator: 0,
        coverage: null,
      }),
      NO_ELECTRIC,
    ]);
    const { unmount } = setup(READ, { ...base, getAnalyticsMetrics: () => answer });
    await auswerten();
    await objektOeffnen();
    const kraftstoff = await screen.findByRole('region', {
      name: 'Energiekosten Kraftstoff (Liter)',
    });
    expect(within(kraftstoff).getByText(/nicht berechenbar/)).toBeInTheDocument();
    expect(
      within(kraftstoff).getByText(/Nenner 0 \(keine Kilometer im Zeitraum\)/),
    ).toBeInTheDocument();
    expect(within(kraftstoff).queryByText(/je 100 km/)).toBeNull(); // kein Scheinwert
    unmount();
    // vorläufig: Wert mit Abdeckungsgrad und Ausschlussliste
    answer = metrics([
      metric({
        state: 'PROVISIONAL',
        coverage: 0.5,
        exclusions: [
          {
            document_id: '00000000-0000-4000-8000-0000000000d9',
            vehicle_id: VEHICLE,
            reason: 'NOT_RELEASED',
          },
        ],
      }),
      NO_ELECTRIC,
    ]);
    setup(READ, { ...base, getAnalyticsMetrics: () => answer });
    await auswerten();
    await objektOeffnen();
    const vorlaeufig = await screen.findByRole('region', {
      name: 'Energiekosten Kraftstoff (Liter)',
    });
    expect(within(vorlaeufig).getByText(/vorläufig, Abdeckung 50,0 %/)).toBeInTheDocument();
    expect(within(vorlaeufig).getByText(/1 Tankvorgänge nicht berücksichtigt/)).toBeInTheDocument();
    expect(
      within(vorlaeufig).getByText(/nicht freigegeben oder nicht gebucht/),
    ).toBeInTheDocument();
  });

  it('datenluecke_ladefehler_und_ungueltige_eingabe', async () => {
    let answer: unknown = { ...CLASSIFICATION, rows: [], totals: { ...ZERO }, event_count: 0 };
    setup(READ, { ...base, getCostClassification: () => answer });
    await screen.findByRole('option', { name: 'Test GmbH' });
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled(); // ohne Periode kein Aufruf
    fireEvent.change(screen.getByLabelText(/Beginn der Periode/), {
      target: { value: '2026-12-01' },
    });
    fireEvent.change(screen.getByLabelText(/Ende der Periode/), {
      target: { value: '2026-09-01' },
    });
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled(); // Ende vor Beginn
    fireEvent.change(screen.getByLabelText(/Beginn der Periode/), {
      target: { value: '2026-09-01' },
    });
    fireEvent.change(screen.getByLabelText(/Ende der Periode/), {
      target: { value: '2026-12-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
    expect(await screen.findByText('Keine gebuchten Kosten im Zeitraum')).toBeInTheDocument();
    expect(screen.queryByText(/0,00 EUR/)).toBeNull(); // kein Scheinwert
    answer = new ApiError(503);
    fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });

  it('ohne_finanzrecht_keine_auswertung_und_nur_eigene_rechtstraeger_waehlbar', async () => {
    // ohne costs:read: keine Rechtsträger, keine Auswertung, Auswerten gesperrt
    const none = setup([], base);
    await screen.findByText(/Kostenübersicht nicht freigeschaltet/);
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    expect(
      none.calls.some((c) => c.id === 'listLegalEntities' || c.id === 'getCostClassification'),
    ).toBe(false);
    none.unmount();
    // mit Recht: nur die vom Server gelieferten Rechtsträger sind wählbar (der Server filtert auf den Mandanten); ein fremder ist nicht auswählbar
    const { calls } = setup(READ, base);
    const select = await screen.findByLabelText('Rechtsträger');
    await screen.findByRole('option', { name: 'Test GmbH' });
    expect(
      within(select)
        .getAllByRole('option')
        .map((o) => (o as HTMLOptionElement).value),
    ).toEqual([ENTITY]);
    expect(within(select).queryByDisplayValue(FOREIGN_ENTITY)).toBeNull();
    fireEvent.change(screen.getByLabelText(/Beginn der Periode/), {
      target: { value: '2026-09-01' },
    });
    fireEvent.change(screen.getByLabelText(/Ende der Periode/), {
      target: { value: '2026-12-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
    await waitFor(() => expect(calls.find((c) => c.id === 'getCostClassification')).toBeTruthy());
    expect(calls.find((c) => c.id === 'getCostClassification')?.args).toMatchObject({
      query: { legal_entity_id: ENTITY },
    });
    // fehlende Kennzahlenberechtigung: Kosten sichtbar, Energiekosten mit Hinweis
  });
});
