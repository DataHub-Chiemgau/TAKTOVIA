import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, ServiceOfferModelRules } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const MODELS: ServiceOfferModelRules[] = [
  {
    model: 'AREA_ON_DEMAND',
    label: 'Flächenbedarfsverkehr',
    required: ['max_detour_pct'],
    forbidden: ['advance_arrival_buffer_min'],
    optional: ['pooling_allowed', 'stop_walk_max_m'],
  },
  {
    model: 'LINE_ON_DEMAND',
    label: 'linienbasierter Bedarfsverkehr',
    required: ['stop_walk_max_m'],
    forbidden: ['advance_arrival_buffer_min', 'max_detour_pct'],
    optional: ['pooling_allowed'],
  },
  {
    model: 'STATION_FEEDER',
    label: 'Bahnhofszubringer',
    required: ['advance_arrival_buffer_min'],
    forbidden: [],
    optional: ['max_detour_pct', 'pooling_allowed', 'stop_walk_max_m'],
  },
];
const ID = (n: number) => `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(handlers: Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({
            roles: ['tenant_admin'],
            scopes: ['me:read', 'config:read', 'config:write', 'services:read', 'services:write'],
          }),
        );
      if (id === 'listServiceOfferModels') return Promise.resolve({ items: MODELS });
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/dienstkonfiguration']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

function fill(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe('UI-17 Basis: Dienst anlegen mit Angebotsmodell', () => {
  it('ui17_modellabhaengige_felder_pflicht_optional_und_unzulaessig_entfaellt', async () => {
    const { container } = setup({});
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Dienst anlegen' }),
    ).toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText('Angebotsmodell'), {
      target: { value: 'AREA_ON_DEMAND' },
    });
    expect(screen.getByLabelText(/Maximaler Umweg \(%\) \(Pflicht\)/)).toBeRequired();
    expect(screen.getByLabelText(/Bündelung erlaubt \(optional\)/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Ankunftspuffer/)).not.toBeInTheDocument(); // unzulässig
    fireEvent.change(screen.getByLabelText('Angebotsmodell'), {
      target: { value: 'LINE_ON_DEMAND' },
    });
    expect(
      screen.getByLabelText(/Maximaler Fußweg zum Haltepunkt \(m\) \(Pflicht\)/),
    ).toBeRequired();
    expect(screen.queryByLabelText(/Maximaler Umweg/)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Angebotsmodell'), {
      target: { value: 'STATION_FEEDER' },
    });
    expect(
      screen.getByLabelText(/Ankunftspuffer vor Bahnabfahrt \(min\) \(Pflicht\)/),
    ).toBeRequired();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ui17_legt_dienst_und_entwurf_der_dienstkonfiguration_an_ohne_unzulaessige_felder', async () => {
    const { calls } = setup({
      createService: () => ({
        id: ID(1),
        operator_id: ID(9),
        name: 'Rufbus',
        timezone: 'Europe/Berlin',
        config_revision_id: ID(2),
        revision: 1,
      }),
      updateConfigRevision: () => ({}),
    });
    fireEvent.change(await screen.findByLabelText('Angebotsmodell'), {
      target: { value: 'LINE_ON_DEMAND' },
    });
    fill('Dienstname', 'Rufbus');
    fill('Betreiber (Kennung)', ID(9));
    fill('Bediengebiet (Kennung)', ID(3));
    fill('Tarif (Kennung)', ID(4));
    fill(/Maximaler Fußweg zum Haltepunkt/, '400');
    fireEvent.click(screen.getByRole('button', { name: 'Dienst anlegen' }));
    expect(
      await screen.findByText(/Entwurf der Dienstkonfiguration wurden angelegt/),
    ).toBeInTheDocument();
    const create = calls.find((c) => c.id === 'createService');
    expect(create?.args).toMatchObject({ body: { operator_id: ID(9), name: 'Rufbus' } });
    const upd = calls.find((c) => c.id === 'updateConfigRevision');
    const body = (upd?.args.body ?? {}) as { values: Record<string, unknown> };
    expect(upd?.args).toMatchObject({ path: { revision_id: ID(2) }, revision: 1 });
    expect(body.values).toMatchObject({
      stop_walk_max_m: 400,
      service_config: { traffic_type: 'LINE_ON_DEMAND', operator_id: ID(9) },
    });
    expect(Object.keys(body.values)).not.toContain('max_detour_pct');
    expect(JSON.stringify(calls)).not.toMatch(/tenant_id/);
  });

  it('ui17_zeigt_feldfehler_bei_422_und_fehlendes_recht', async () => {
    const violation = new ApiError(422, {
      type: 'about:blank',
      title: 'x',
      status: 422,
      code: 'VALIDATION_FAILED',
      correlation_id: ID(7),
      violations: [
        {
          pointer: '/values/max_detour_pct',
          code: 'REQUIRED',
          message: 'Für dieses Angebotsmodell erforderlich',
        },
      ],
    });
    setup({
      createService: () => ({
        id: ID(1),
        operator_id: ID(9),
        name: 'x',
        timezone: 'Europe/Berlin',
        config_revision_id: ID(2),
        revision: 1,
      }),
      updateConfigRevision: () => violation,
    });
    fireEvent.change(await screen.findByLabelText('Angebotsmodell'), {
      target: { value: 'STATION_FEEDER' },
    });
    fill('Dienstname', 'x');
    fill('Betreiber (Kennung)', ID(9));
    fill('Bediengebiet (Kennung)', ID(3));
    fill('Tarif (Kennung)', ID(4));
    fill(/Ankunftspuffer/, '15');
    fireEvent.click(screen.getByRole('button', { name: 'Dienst anlegen' }));
    expect(await screen.findByText(/Die Eingabe wurde abgewiesen \(422\)/)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText(/Für dieses Angebotsmodell erforderlich/)).toBeInTheDocument(),
    );
  });
});
