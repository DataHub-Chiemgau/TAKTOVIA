import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { buildRule } from './Tarife';

const ID = (n: number) => `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;
const SERVICE = {
  id: ID(1),
  operator_id: ID(9),
  name: 'Rufbus Nord',
  timezone: 'Europe/Berlin',
  config_revision_id: ID(2),
  revision: 1,
};
const PAGE = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-12-01T00:00:00Z' });
const REVISION = (state: string, revision: number, no = 1) => ({
  id: ID(30 + no),
  service_id: ID(1),
  tariff_class: 'STANDARD',
  revision_no: no,
  state,
  valid_from: '2026-12-01T00:00:00Z',
  valid_to: null,
  currency: 'EUR',
  reason: 'Wintertarif',
  revision,
  created_by: 'user:ersteller',
  created_at: '2026-11-01T00:00:00Z',
  reviewed_by: null,
  reviewed_at: null,
  published_by: null,
  published_at: null,
  rules: [
    {
      id: ID(50),
      rule_no: 1,
      rule_type: 'FESTPREIS',
      person_mode: 'PER_PERSON',
      amount_minor: 450,
      zone_from: null,
      zone_to: null,
      rate_per_km: null,
      booking_fee_minor: 0,
      tax_rate: '0.07000000',
      tax_mode: 'GROSS',
      tax_rule_revision: 'DE-2026-1',
    },
  ],
});

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (args: Record<string, unknown>) => unknown;

function setup(
  handlers: Partial<Record<OperationId, Handler>>,
  scopes = [
    'me:read',
    'config:read',
    'config:write',
    'services:read',
    'tariffs:read',
    'tariffs:write',
    'tariffs:publish',
  ],
) {
  const calls: Call[] = [];
  const base: Partial<Record<OperationId, Handler>> = { listServices: () => PAGE([SERVICE]) };
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['tenant_admin'], scopes }));
      const h = handlers[id] ?? base[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/tarife']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const problem = (
  status: number,
  code: string,
  violations: { pointer: string; code: string; message: string }[] = [],
) =>
  new ApiError(status, {
    type: 'about:blank',
    title: 'x',
    status,
    code,
    correlation_id: ID(99),
    violations,
  } as never);

describe('UI-17 Tarife (WP-M2-07)', () => {
  it('ui17_tarife_leer_zustand_anlegen_mit_pflichtangaben_und_axe', async () => {
    const { calls, container } = setup({
      listTariffRevisions: () => PAGE([]),
      createTariffRevision: () => REVISION('ENTWURF', 1),
    });
    expect(await screen.findByRole('heading', { level: 1, name: 'Tarife' })).toBeInTheDocument();
    expect(await screen.findByText('Noch keine Tarifrevision')).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
    fireEvent.change(screen.getByLabelText('Betrag (kleinste Einheit)'), {
      target: { value: '450' },
    });
    fireEvent.change(screen.getByLabelText('Steuerregelrevision'), {
      target: { value: 'DE-2026-1' },
    });
    fireEvent.change(screen.getByLabelText('Grund'), { target: { value: 'Wintertarif' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tarifrevision anlegen' }));
    expect(await screen.findByText(/als Entwurf angelegt/)).toBeInTheDocument();
    const create = calls.find((c) => c.id === 'createTariffRevision');
    expect(create?.args).toMatchObject({
      body: {
        service_id: ID(1),
        tariff_class: 'STANDARD',
        currency: 'EUR',
        reason: 'Wintertarif',
        valid_to: null,
        rules: [
          {
            rule_type: 'FESTPREIS',
            amount_minor: 450,
            tax_rate: '0.07',
            tax_rule_revision: 'DE-2026-1',
          },
        ],
      },
    });
    expect(JSON.stringify(calls)).not.toMatch(/tenant_id/);
  });

  it('ui17_tarife_pruefen_und_freigeben_mit_revision_und_zustand_veroeffentlicht', async () => {
    let state = 'ENTWURF';
    let revision = 1;
    const { calls } = setup({
      listTariffRevisions: () => PAGE([REVISION(state, revision)]),
      reviewTariffRevision: () => {
        state = 'GEPRUEFT';
        revision = 2;
        return REVISION(state, revision);
      },
      publishTariffRevision: () => {
        state = 'VEROEFFENTLICHT';
        revision = 3;
        return REVISION(state, revision);
      },
    });
    expect(await screen.findByRole('table', { name: 'Tarifrevisionen' })).toBeInTheDocument();
    expect(
      within(screen.getByRole('table', { name: 'Tarifrevisionen' })).getByText('Festpreis'),
    ).toBeInTheDocument();
    expect(screen.getByText('offen')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Revision 1 prüfen' }));
    expect(await screen.findByText(/wurde geprüft/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Revision 1 freigeben' }));
    expect(
      await screen.findByText(/veröffentlicht und ist jetzt unveränderlich/),
    ).toBeInTheDocument();
    expect(await screen.findByText('unveränderlich')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /prüfen|freigeben/ })).toBeNull();
    expect(calls.find((c) => c.id === 'reviewTariffRevision')?.args).toMatchObject({
      path: { tariff_id: ID(31) },
      revision: 1,
    });
    expect(calls.find((c) => c.id === 'publishTariffRevision')?.args).toMatchObject({
      path: { tariff_id: ID(31) },
      revision: 2,
    });
  });

  it('ui17_tarife_konkurrierende_version_ueberlappung_vier_augen_und_422', async () => {
    let n = 0;
    setup({
      listTariffRevisions: () => PAGE([REVISION('ENTWURF', 1), REVISION('GEPRUEFT', 2, 2)]),
      reviewTariffRevision: () => problem(412, 'REVISION_MISMATCH'),
      publishTariffRevision: () =>
        ++n === 1 ? problem(403, 'FORBIDDEN') : problem(409, 'CONFLICT'),
      createTariffRevision: () =>
        problem(422, 'VALIDATION_FAILED', [
          { pointer: '/valid_from', code: 'REQUIRED', message: 'Pflichtfeld fehlt' },
        ]),
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Revision 1 prüfen' }));
    expect(await screen.findByText(/Konkurrierende Version \(412\)/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Revision 2 freigeben' }));
    expect(await screen.findByText(/zweite Person/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Revision 2 freigeben' }));
    expect(await screen.findByText(/überschneidet sich/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Betrag (kleinste Einheit)'), {
      target: { value: '450' },
    });
    fireEvent.change(screen.getByLabelText('Steuerregelrevision'), { target: { value: 'x' } });
    fireEvent.change(screen.getByLabelText('Grund'), { target: { value: 'g' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tarifrevision anlegen' }));
    expect(await screen.findByText(/Die Eingabe wurde abgewiesen \(422\)/)).toBeInTheDocument();
    expect(screen.getByText('/valid_from')).toBeInTheDocument();
  });

  it('ui17_tarife_ohne_recht_403_zeigt_fehlerzustand_ohne_daten', async () => {
    setup({ listTariffRevisions: () => problem(403, 'FORBIDDEN') });
    await waitFor(() =>
      expect(
        screen
          .getAllByTestId('state-view')
          .some((e) => e.getAttribute('data-kind') === 'forbidden'),
      ).toBe(true),
    );
    expect(screen.queryByRole('table', { name: 'Tarifrevisionen' })).toBeNull();
  });

  it('regelaufbau_je_regeltyp', () => {
    const base = {
      amount: '450',
      rate: '',
      zoneFrom: '',
      zoneTo: '',
      taxRate: '',
      taxRevision: 'T1',
    };
    expect(buildRule({ ...base, type: 'FESTPREIS' })).toEqual({
      rule_type: 'FESTPREIS',
      amount_minor: 450,
      tax_rate: '0',
      tax_rule_revision: 'T1',
    });
    expect(
      buildRule({ ...base, type: 'ZONENPREIS', zoneFrom: ID(1), zoneTo: ID(2) }),
    ).toMatchObject({ zone_from: ID(1), zone_to: ID(2) });
    expect(buildRule({ ...base, type: 'GRUNDPREIS_STRECKE', rate: '0.35' })).toMatchObject({
      rate_per_km: '0.35',
    });
    expect(buildRule({ ...base, type: 'FESTPREIS', amount: '4.5' }).amount_minor).toBeNaN();
  });
});
