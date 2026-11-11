import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  ConfigParameterCatalog,
  ConfigRevision,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const TENANT = '00000000-0000-4000-8000-000000000001';

const REV = (over: Partial<ConfigRevision>): ConfigRevision => ({
  id: '00000000-0000-4000-8000-0000000000c1',
  scope_kind: 'TENANT',
  scope_id: TENANT,
  revision: 1,
  state: 'PUBLISHED',
  schema_version: '1',
  values: { max_wait_min: 20 },
  effective_from: '2026-03-01T00:00:00Z',
  effective_to: null,
  created_by: 'user:alice',
  approved_by: 'user:bob',
  reason: 'Erstfassung',
  created_at: '2026-02-20T09:00:00Z',
  published_at: '2026-02-21T09:00:00Z',
  change_log: {
    changed_by: 'user:alice',
    changed_at: '2026-02-20T09:00:00Z',
    reason: 'Erstfassung',
    effective_from: '2026-03-01T00:00:00Z',
    effective_to: null,
    predecessor_id: null,
    predecessor_revision: null,
    changes: [{ pointer: '/max_wait_min', kind: 'ADDED', new_value: 20 }],
  },
  ...over,
});

const CATALOG: ConfigParameterCatalog = {
  schema_version: '1',
  valid_from: '2026-01-01T00:00:00Z',
  items: [
    {
      name: 'max_wait_min',
      type: 'integer',
      unit: 'MINUTES',
      minimum: 0,
      maximum: 240,
      allowed_levels: ['TENANT', 'SERVICE'],
      security_class: 'STANDARD',
      limit_source: 'KATALOG',
      description: 'Maximale Wartezeit',
      schema: {},
    },
    {
      name: 'vehicle_readiness_require_checks',
      type: 'boolean',
      unit: 'BOOLEAN',
      minimum: null,
      maximum: null,
      allowed_levels: ['TENANT'],
      security_class: 'SAFETY_INVARIANT',
      limit_source: 'KATALOG',
      description: 'Pflichtprüfung vor Dienst',
      schema: {},
    },
  ],
};

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(
  revisions: ConfigRevision[] | ApiError,
  roles = ['tenant_admin'],
  catalog: ConfigParameterCatalog | ApiError = CATALOG,
) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe')
        return Promise.resolve(
          sampleMe({
            roles,
            scopes: roles.includes('tenant_admin')
              ? ['me:read', 'config:read', 'config:write']
              : ['me:read'],
          }),
        );
      if (id === 'listConfigRevisions') {
        if (revisions instanceof ApiError) return Promise.reject(revisions);
        const query = args.query as Record<string, string> | undefined;
        if (query?.as_of)
          return Promise.resolve({
            items: revisions
              .filter((r) => r.state === 'PUBLISHED' && r.effective_from <= (query.as_of ?? ''))
              .slice(-1),
            next_cursor: null,
            as_of: query.as_of,
          });
        return Promise.resolve({
          items: revisions,
          next_cursor: null,
          as_of: '2026-11-02T08:00:00Z',
        });
      }
      if (id === 'listConfigParameters')
        return catalog instanceof ApiError ? Promise.reject(catalog) : Promise.resolve(catalog);
      return Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/gebiete']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-17 Basis: Konfigurationsrevisionen und Parameterkatalog', () => {
  it('ui17_revisionsliste_zeigt_ersteller_zeitpunkt_wirksamkeit_grund_und_diff_alt_neu', async () => {
    const second = REV({
      id: '00000000-0000-4000-8000-0000000000c2',
      revision: 2,
      state: 'DRAFT',
      reason: 'Sommerfahrplan',
      created_by: 'user:bob',
      effective_from: '2026-07-01T00:00:00Z',
      change_log: {
        changed_by: 'user:bob',
        changed_at: '2026-06-01T09:00:00Z',
        reason: 'Sommerfahrplan',
        effective_from: '2026-07-01T00:00:00Z',
        effective_to: null,
        predecessor_id: '00000000-0000-4000-8000-0000000000c1',
        predecessor_revision: 1,
        changes: [{ pointer: '/max_wait_min', kind: 'CHANGED', old_value: 20, new_value: 35 }],
      },
    });
    const { calls, container } = setup([REV({}), second]);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Gebiete, Stops, Tarife' }),
    ).toBeInTheDocument();
    const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
    expect(ctx).toHaveTextContent('Verkehrsbetrieb Nord');
    expect(ctx).toHaveTextContent('Datenstand');
    const table = await screen.findByRole('table', { name: /Revisionen/ });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    const r2 = rows[2] as HTMLElement;
    expect(r2).toHaveTextContent('user:bob'); // Ersteller
    expect(r2).toHaveTextContent('Entwurf');
    expect(r2).toHaveTextContent('Sommerfahrplan'); // Grund
    expect(r2).toHaveTextContent('ab 2026-07-01'); // Gültigkeitsbeginn
    expect(r2).toHaveTextContent('/max_wait_min'); // Diff Alt/Neu
    expect(r2).toHaveTextContent('20 → 35');
    expect(rows[1]).toHaveTextContent('neu → 20');
    expect(r2).toHaveTextContent('00000000-0000-4000-8000-0000000000c2'); // Revisions-ID wird immer angezeigt
    // die Anfrage trägt den Scope, aber keinen Mandanten außerhalb des Scopes: der Server bestimmt den Mandanten
    const list = calls.find((c) => c.id === 'listConfigRevisions');
    expect(list?.args).toMatchObject({ query: { scope_kind: 'TENANT', scope_id: TENANT } });
    expect(JSON.stringify(list?.args)).not.toMatch(/tenant_id/);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ui17_parameterkatalog_zeigt_name_typ_einheit_bereich_und_sicherheitsparameter', async () => {
    setup([REV({})]);
    const table = await screen.findByRole('table', { name: /Parameterkatalog, Schema-Version 1/ });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('max_wait_min');
    expect(rows[1]).toHaveTextContent('integer');
    expect(rows[1]).toHaveTextContent('MINUTES');
    expect(rows[1]).toHaveTextContent('0 bis 240');
    expect(rows[1]).toHaveTextContent('Maximale Wartezeit');
    expect(rows[2]).toHaveTextContent('nicht abschaltbar');
    expect(rows[2]).toHaveTextContent('ja / nein');
  });

  it('ui17_rekonstruktion_nennt_die_damals_wirksame_revision_mit_id', async () => {
    const r2 = REV({
      id: '00000000-0000-4000-8000-0000000000c2',
      revision: 2,
      effective_from: '2026-06-01T00:00:00Z',
      reason: 'Zweiter Stand',
    });
    const { calls } = setup([REV({}), r2]);
    await screen.findByRole('table', { name: /Revisionen/ });
    fireEvent.change(screen.getByLabelText('Zeitpunkt (RFC 3339 mit Offset)'), {
      target: { value: '2026-07-01T00:00:00+02:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Wirksame Revision anzeigen' }));
    expect(await screen.findByRole('status')).toHaveTextContent(
      'wirkte Revision 2 (00000000-0000-4000-8000-0000000000c2), Grund: Zweiter Stand',
    );
    expect(
      calls.find((c) => (c.args.query as Record<string, string> | undefined)?.as_of)?.args,
    ).toMatchObject({
      query: { scope_kind: 'TENANT', scope_id: TENANT, as_of: '2026-07-01T00:00:00+02:00' },
    });
    // ohne Offset wird gar nicht erst angefragt
    const before = calls.length;
    fireEvent.change(screen.getByLabelText('Zeitpunkt (RFC 3339 mit Offset)'), {
      target: { value: '2026-07-01T00:00:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Wirksame Revision anzeigen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('mit Offset');
    expect(calls.length).toBe(before);
  });

  it('ui17_zustaende_leer_fehler_und_fehlende_rechte', async () => {
    const empty = setup([]);
    await waitFor(() =>
      expect(
        screen.getAllByTestId('state-view').some((e) => e.getAttribute('data-kind') === 'empty'),
      ).toBe(true),
    );
    expect(screen.getByText(/noch keine Konfigurationsrevision/)).toBeInTheDocument();
    empty.unmount();

    const failed = setup(new ApiError(503));
    await waitFor(() =>
      expect(
        screen
          .getAllByTestId('state-view')
          .some((e) => e.getAttribute('data-kind') === 'unavailable'),
      ).toBe(true),
    );
    failed.unmount();

    const forbidden = setup(new ApiError(403), ['tenant_admin'], new ApiError(403));
    await waitFor(() =>
      expect(
        screen
          .getAllByTestId('state-view')
          .every((e) => e.getAttribute('data-kind') === 'forbidden'),
      ).toBe(true),
    );
    expect(screen.queryByRole('table')).toBeNull();
    forbidden.unmount();

    // ohne Recht auf den Bereich: Rechtezustand ohne eine einzige Anfrage der Konfiguration
    const { calls } = setup([REV({})], ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(
      calls.some((c) => c.id === 'listConfigRevisions' || c.id === 'listConfigParameters'),
    ).toBe(false);
  });
});
