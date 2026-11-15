import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  AnalyticsMetrics,
  ApiClient,
  MetricDefinition,
  MetricValue,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { RequireRight } from '../Screen';
import { Kennzahlen, formatValue } from './Kennzahlen';

type Call = { id: OperationId; args: { query?: Record<string, unknown> } };
type Server = (query: Record<string, unknown>) => AnalyticsMetrics | ApiError;

/** Modell des Servers für die Ansicht (kein Ersatz für die Backend-Tests MetricsFilterTest/ReportFilterTest; ein Browser-E2E ist hier NICHT ausgeführt). */
function setup(scopes: string[], server: Server) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Call['args']) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ subject: 'betrieb-1', roles: ['dispatcher'], scopes }));
      if (id !== 'getAnalyticsMetrics') return Promise.reject(new ApiError(404));
      const r = server(args.query ?? {});
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter>
      <SessionProvider client={client}>
        <Kennzahlen />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const def = (
  metric_id: string,
  name: string,
  kind: MetricDefinition['value_kind'],
  unit: string,
): MetricDefinition => ({
  metric_id,
  version: 1,
  name,
  value_kind: kind,
  unit,
  numerator: `Zähler von ${name}`,
  denominator: `Nenner von ${name}`,
  exclusions: 'Testanfragen',
  time_basis: 'Betriebstag',
  period_basis: 'BUSINESS_DAY_LOCAL',
  filter_dimensions: ['service_id'],
  report_dimensions: ['operator_id'],
  executable: true,
  not_executable_reason: null,
});
const DEFS: MetricDefinition[] = [
  def('requests', 'Anfragen', 'COUNT', 'Anfragen'),
  def('offer_rate', 'Angebotsquote', 'RATIO', 'Anteil'),
  def('pickup_on_time', 'Abholung pünktlich', 'RATIO', 'Anteil'),
  def('arrival_on_time', 'Ankunft pünktlich', 'RATIO', 'Anteil'),
  def('productive_km', 'Produktive Kilometer', 'SUM', 'km'),
  def('no_show_rate', 'No-Show-Rate', 'RATIO', 'Anteil'),
];
const period = {
  from: '2026-06-01',
  to: '2026-06-30',
  timezone: 'Europe/Berlin',
  from_instant: '2026-05-31T22:00:00Z',
  to_instant: '2026-06-30T22:00:00Z',
};
const val = (id: string, o: Partial<MetricValue>): MetricValue => ({
  metric_id: id,
  definition_version: 1,
  state: 'OK',
  reason: null,
  value: 1,
  numerator: 1,
  denominator: 1,
  coverage: 1,
  unit: 'Anfragen',
  filter: {},
  period,
  as_of: '2026-06-30T21:55:00Z',
  source_events: 10,
  snapshot_id: null,
  exclusions: [],
  approximation: null,
  ...o,
});
const VALUES: MetricValue[] = [
  val('requests', { value: 120, numerator: 120, denominator: null }),
  val('offer_rate', { value: 0.75, numerator: 90, denominator: 120, unit: 'Anteil' }),
  val('pickup_on_time', { value: 0.9, numerator: 45, denominator: 50, unit: 'Anteil' }),
  val('arrival_on_time', { value: 0.8, numerator: 40, denominator: 50, unit: 'Anteil' }),
  val('productive_km', { value: 321.5, numerator: 321.5, denominator: null, unit: 'km' }),
  val('no_show_rate', {
    state: 'SUPPRESSED',
    reason: 'SMALL_GROUP',
    value: null,
    numerator: null,
    denominator: null,
    unit: 'Anteil',
  }),
];
const FUNNEL = {
  period,
  filter: {},
  counts: { requests: 120, offers: 90, confirmed_bookings: 60, completed_bookings: 55 },
  end_states: {
    request_rejected: 30,
    request_technical_error: 2,
    offer_not_booked: 30,
    payment_failed: 1,
    cancelled_by_passenger: 4,
    cancelled_by_operator: 1,
    cancelled_other: 0,
  },
  unlinked_bookings: 0,
  state: 'OK',
  suppressed: false,
  coverage: 1,
  as_of: '2026-06-30T21:55:00Z',
  source_events: 300,
} as const;
const REJ = {
  attempts: 120,
  rejections: 30,
  technical_errors: 2,
  state: 'OK',
  suppressed: false,
  coverage: 1,
  as_of: '2026-06-30T21:55:00Z',
  source_events: 120,
  reason_code: null,
  rows: [
    {
      code: 'CAPACITY_FULL',
      group: 'CAPACITY',
      metric: 'CAPACITY',
      text: 'Keine Kapazität',
      texts: {},
      primary_count: 20,
      secondary_count: 3,
    },
    {
      code: 'AREA_OUTSIDE',
      group: 'AREA',
      metric: 'AREA',
      text: 'Außerhalb des Bediengebiets',
      texts: {},
      primary_count: 10,
      secondary_count: 0,
    },
  ],
} as const;

const ok: Server = (q) => ({
  definitions: DEFS,
  values: q.breakdown ? null : VALUES,
  rejections: q.breakdown === 'rejection_reason' ? (REJ as never) : null,
  funnel: q.breakdown === 'funnel' ? (FUNNEL as never) : null,
  not_performed: null,
  as_of: '2026-06-30T21:55:00Z',
});

function fill(extra: Record<string, string> = {}) {
  fireEvent.change(screen.getByLabelText('Erster Betriebstag (JJJJ-MM-TT)'), {
    target: { value: '2026-06-01' },
  });
  fireEvent.change(screen.getByLabelText('Letzter Betriebstag (JJJJ-MM-TT)'), {
    target: { value: '2026-06-30' },
  });
  for (const [label, value] of Object.entries(extra))
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Auswerten' }));
}

describe('Kennzahlenansicht (WP-M4-59, E2E-080 bis E2E-084)', () => {
  it('funnel_und_ablehnungsstatistik_sind_sichtbar_und_filterbar', async () => {
    const { calls, container } = setup(['analytics:read'], ok);
    await screen.findByRole('button', { name: 'Auswerten' });
    fill({
      'Betreiber (Kennung)': '00000000-0000-4000-8000-0000000000a1',
      'Ortsstunde (0 bis 23)': '8',
    });
    const funnel = await screen.findByRole('table', { name: 'Trichter-Zählgrößen' });
    // vier getrennte Zählgrößen, keine Summe
    expect(within(funnel).getByRole('row', { name: /^Anfragen\s*120/ })).toBeInTheDocument();
    expect(
      within(funnel).getByRole('row', { name: /^Angebote angezeigt\s*90/ }),
    ).toBeInTheDocument();
    expect(
      within(funnel).getByRole('row', { name: /^Bestätigte Buchungen\s*60/ }),
    ).toBeInTheDocument();
    expect(
      within(funnel).getByRole('row', { name: /^Abgeschlossene Buchungen\s*55/ }),
    ).toBeInTheDocument();
    expect(within(funnel).getAllByRole('row')).toHaveLength(5);
    // Ablehnungen nach Grund in einer Spalte
    const rej = screen.getByRole('table', { name: 'Ablehnungsgründe' });
    expect(within(rej).getByRole('columnheader', { name: 'Ablehnungsgrund' })).toBeInTheDocument();
    expect(within(rej).getByRole('rowheader', { name: 'Keine Kapazität' })).toBeInTheDocument();
    // Filter gelangen an den Server, drei Abfragen (Werte, Trichter, Ablehnungen)
    const qs = calls.filter((c) => c.id === 'getAnalyticsMetrics').map((c) => c.args.query);
    expect(qs).toHaveLength(3);
    for (const q of qs)
      expect(q).toMatchObject({
        from: '2026-06-01',
        to: '2026-06-30',
        operator_id: '00000000-0000-4000-8000-0000000000a1',
        hour: 8,
      });
    expect(qs.map((q) => String(q?.breakdown)).sort()).toEqual([
      'funnel',
      'rejection_reason',
      'undefined',
    ]);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('abholung_und_ankunft_getrennt_mit_zaehler_nenner_definition_und_datenstand_sowie_formelverweis', async () => {
    setup(['analytics:read'], ok);
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    const pickup = await screen.findByRole('table', { name: 'Pünktlichkeit der Abholung' });
    const arrival = screen.getByRole('table', { name: 'Pünktlichkeit der Ankunft' });
    expect(within(pickup).getByText('90 %')).toBeInTheDocument();
    expect(within(arrival).getByText('80 %')).toBeInTheDocument();
    const row = within(pickup).getByRole('row', { name: /^Abholung pünktlich/ });
    expect(within(row).getByText('45')).toBeInTheDocument();
    expect(within(row).getByText('50')).toBeInTheDocument();
    expect(within(row).getByText(/Version 1/)).toBeInTheDocument();
    expect(within(row).getByText('2026-06-30T21:55:00Z')).toBeInTheDocument();
    expect(within(row).getByText('Formel')).toBeInTheDocument();
    expect(within(row).getByText('Zähler: Zähler von Abholung pünktlich')).toBeInTheDocument();
    // Kilometer mit Formelverweis
    const km = screen.getByRole('table', { name: 'Kilometer und Betriebsstunden' });
    expect(within(km).getByText('321,5 km')).toBeInTheDocument();
  });

  it('unterdrueckte_kleingruppe_erscheint_als_unterdrueckt_nie_als_null', async () => {
    setup(['analytics:read'], ok);
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    const t = await screen.findByRole('table', { name: 'Auslastung und Nichtdurchführung' });
    const row = within(t).getByRole('row', { name: /^No-Show-Rate/ });
    expect(within(row).getByText('unterdrückt')).toBeInTheDocument();
    expect(within(row).getByText('Unterdrückt (Kleingruppe)')).toBeInTheDocument();
    expect(row).not.toHaveTextContent(/\b0 ?%/);
  });

  it('datenluecke_zeigt_vollstaendigkeit_und_keine_scheingenauigkeit_e2e_084', async () => {
    const gap: Server = (q) => {
      const base = ok(q);
      return {
        ...base,
        values: q.breakdown
          ? null
          : [
              val('pickup_on_time', {
                state: 'PROVISIONAL',
                coverage: 0.62,
                value: 0.9,
                numerator: 45,
                denominator: 50,
                unit: 'Anteil',
              }),
              val('arrival_on_time', {
                state: 'NOT_COMPUTABLE',
                reason: 'NO_DATA',
                value: null,
                numerator: null,
                denominator: null,
                coverage: null,
                unit: 'Anteil',
              }),
              val('offer_rate', {
                state: 'NOT_COMPUTABLE',
                reason: 'ZERO_DENOMINATOR',
                value: null,
                numerator: 0,
                denominator: 0,
                unit: 'Anteil',
              }),
            ],
        funnel:
          q.breakdown === 'funnel'
            ? ({ ...FUNNEL, state: 'PROVISIONAL', coverage: 0.62 } as never)
            : null,
      };
    };
    setup(['analytics:read'], gap);
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(
      await screen.findByText('Geringe Qualität, vorläufig (Abdeckung 62 %)'),
    ).toBeInTheDocument();
    expect(screen.getByText('Keine Daten')).toBeInTheDocument();
    expect(screen.getByText('Nicht berechenbar (Nenner 0)')).toBeInTheDocument();
    // nicht berechenbar zeigt keinen Wert 0 oder 100 %
    const row = screen.getByRole('row', { name: /^Angebotsquote/ });
    expect(within(row).getByText('–', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText(/Vorläufig, Abdeckung 62 %/)).toBeInTheDocument();
  });

  it('leerer_zeitraum_zeigt_keine_daten_und_ladefehler_bietet_wiederholung', async () => {
    const empty: Server = (q) => ({ ...ok(q), values: q.breakdown ? null : [] });
    const a = setup(['analytics:read'], empty);
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(await screen.findByText('Keine Daten im Zeitraum')).toBeInTheDocument();
    a.unmount();
    setup(['analytics:read'], () => new ApiError(503));
    await screen.findByRole('button', { name: 'Auswerten' });
    fill();
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });

  it('drei_benutzer_mit_gleichen_filtern_sehen_dieselben_werte_und_denselben_datenstand', async () => {
    const texts: string[] = [];
    for (let i = 0; i < 3; i++) {
      const v = setup(['analytics:read'], ok);
      await screen.findByRole('button', { name: 'Auswerten' });
      fill();
      await screen.findByRole('table', { name: 'Trichter-Zählgrößen' });
      texts.push(
        (v.container.querySelector('section[aria-label="Anfragen und Buchungen"]')?.textContent ??
          'FEHLT') + (v.container.textContent?.match(/Datenstand \d{4}-[^ ]+/)?.[0] ?? ''),
      );
      v.unmount();
    }
    expect(new Set(texts).size).toBe(1);
    expect(texts[0]).toContain('Datenstand 2026-06-30T21:55:00Z');
  });

  it('ohne_berichtsrecht_ist_die_ansicht_nicht_erreichbar_und_ruft_nichts_ab', async () => {
    const a = setup([], ok);
    expect(await screen.findByText(/nicht freigeschaltet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    expect(a.calls.some((c) => c.id === 'getAnalyticsMetrics')).toBe(false);
    a.unmount();
    // der Routenwächter zeigt den Rechtezustand ohne Daten
    const client = {
      call: vi.fn(() =>
        Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes: ['operator:read'] })),
      ),
    } as unknown as ApiClient;
    render(
      <MemoryRouter>
        <SessionProvider client={client}>
          <RequireRight right="analytics">
            <Kennzahlen />
          </RequireRight>
        </SessionProvider>
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole('heading', {
        name: /keine berechtigung|kein zugriff|nicht berechtigt/i,
      }),
    ).toBeInTheDocument();
  });

  it('ungueltige_filter_werden_vor_der_abfrage_abgewiesen', async () => {
    const { calls } = setup(['analytics:read'], ok);
    await screen.findByRole('button', { name: 'Auswerten' });
    fireEvent.change(screen.getByLabelText('Erster Betriebstag (JJJJ-MM-TT)'), {
      target: { value: '2026-06-30' },
    });
    fireEvent.change(screen.getByLabelText('Letzter Betriebstag (JJJJ-MM-TT)'), {
      target: { value: '2026-06-01' },
    });
    expect(screen.getByRole('alert')).toHaveTextContent('liegt nach');
    expect(screen.getByRole('button', { name: 'Auswerten' })).toBeDisabled();
    expect(calls.some((c) => c.id === 'getAnalyticsMetrics')).toBe(false);
  });

  it('formatiert_anteile_als_prozent_und_zaehlwerte_mit_einheit', () => {
    const d = DEFS[1];
    expect(formatValue(d, val('offer_rate', { value: 0.123456, unit: 'Anteil' }))).toBe('12,346 %');
    expect(formatValue(DEFS[0], val('requests', { value: 1200, unit: 'Anfragen' }))).toBe(
      '1.200 Anfragen',
    );
    expect(formatValue(d, val('offer_rate', { value: null, state: 'NOT_COMPUTABLE' }))).toBe('–');
  });
});
