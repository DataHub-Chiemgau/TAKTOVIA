import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Organization, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const LE = '00000000-0000-4000-8000-0000000000a1';
const O = (over: Partial<Organization>): Organization => ({
  id: '00000000-0000-4000-8000-0000000000b1',
  legal_entity_id: LE,
  name: 'Verkehrsbetriebe Nord',
  federation_ref: 'fed-nord',
  roles: ['TRANSPORT_COMPANY', 'ACTUAL_CARRIER'],
  roles_valid_from: '2026-11-01T00:00:00Z',
  roles_valid_to: null,
  revision: 1,
  ...over,
});

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(
  organizations: Organization[] | ApiError,
  roles = ['tenant_admin'],
  others: Partial<Record<OperationId, () => unknown>> = {},
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
              ? ['me:read', 'memberships:read', 'organization:read', 'organization:write']
              : ['me:read'],
          }),
        );
      if (id === 'listOrganizations')
        return organizations instanceof ApiError
          ? Promise.reject(organizations)
          : Promise.resolve({
              items: organizations,
              next_cursor: null,
              as_of: '2026-11-02T08:00:00Z',
            });
      if (id === 'listLegalEntities')
        return Promise.resolve({
          items: [{ id: LE, name: 'Stadtwerke GmbH', country: 'DE', revision: 1 }],
          next_cursor: null,
          as_of: '2026-11-02T08:00:00Z',
        });
      const other = others[id];
      return other ? Promise.resolve(other()) : Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/organisationen']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-18 Basis: Organisationen mit Rechtsträgerrollen', () => {
  it('zeigt Organisationen mit ihren getrennten Rollen und ist barrierefrei', async () => {
    const { container, calls } = setup([
      O({}),
      O({
        id: '00000000-0000-4000-8000-0000000000b2',
        name: 'Aufgabenträger Land',
        federation_ref: 'fed-land',
        roles: ['TASK_AUTHORITY'],
      }),
    ]);
    const table = await screen.findByRole('table', {
      name: 'Organisationen des aktiven Mandanten',
    });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getByText('Verkehrsunternehmen, Tatsächlicher Beförderer')).toBeVisible();
    expect(within(table).getByText('Aufgabenträger')).toBeVisible();
    expect(JSON.stringify(calls.find((c) => c.id === 'listOrganizations')?.args)).not.toMatch(
      /tenant/i,
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('rechtezustand: ohne Recht keine Daten, Direktaufruf lädt nichts', async () => {
    const { calls } = setup([O({})], ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(calls.some((c) => c.id === 'listOrganizations')).toBe(false);
    expect(screen.queryByText('Verkehrsbetriebe Nord')).toBeNull();
  });

  it('403 der Liste zeigt den Rechtezustand ohne Daten', async () => {
    setup(new ApiError(403));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('Anlegen sendet Rollen und Rechtsträger, aber keinen Mandanten', async () => {
    const { calls } = setup([], ['tenant_admin'], { createOrganization: () => O({}) });
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: ' Neu ' } });
    fireEvent.change(screen.getByLabelText('Föderationskennung'), { target: { value: 'fed-neu' } });
    fireEvent.change(await screen.findByLabelText('Rechtsträger'), { target: { value: LE } });
    fireEvent.click(screen.getByLabelText('Vermittler'));
    fireEvent.click(screen.getByLabelText('Auftraggeber'));
    fireEvent.click(screen.getByRole('button', { name: 'Anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createOrganization')).toBe(true));
    const create = calls.find((c) => c.id === 'createOrganization');
    const body = (create?.args as { body: { roles: string[] } & Record<string, unknown> }).body;
    expect(body).toMatchObject({ name: 'Neu', federation_ref: 'fed-neu', legal_entity_id: LE });
    expect([...body.roles].sort()).toEqual(['BROKER', 'CLIENT']);
    expect(JSON.stringify(create?.args)).not.toMatch(/tenant/i);
    expect(await screen.findByRole('status')).toHaveTextContent('wurde angelegt');
  });

  it('Scopekonflikt bei doppelter Föderationskennung (409)', async () => {
    setup([], ['tenant_admin'], {
      createOrganization: () => {
        throw new ApiError(409);
      },
    });
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: 'Anlegen' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Scopekonflikt');
  });
});
