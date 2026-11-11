import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Membership, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const M = (over: Partial<Membership>): Membership => ({
  id: '00000000-0000-4000-8000-0000000000e1',
  issuer: 'https://idp.example/realms/test',
  subject: 'user-4711',
  role_code: 'dispatcher',
  service_scope: null,
  revoked_at: null,
  revision: 3,
  ...over,
});

type Call = { id: OperationId; args: Record<string, unknown> };

/** Client, der Aufrufe festhält; `memberships` beantwortet `listMemberships`, alles andere nach `others`. */
function setup(
  memberships: Membership[] | ApiError,
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
              ? ['me:read', 'tenant:switch', 'memberships:read', 'memberships:write']
              : ['me:read', 'tenant:switch'],
            tenants: [
              { tenant_id: '00000000-0000-4000-8000-000000000001', name: 'Verkehrsbetrieb Nord' },
              { tenant_id: '00000000-0000-4000-8000-000000000009', name: 'Fremdbetrieb Süd' },
            ],
          }),
        );
      if (id === 'listMemberships')
        return memberships instanceof ApiError
          ? Promise.reject(memberships)
          : Promise.resolve({
              items: memberships,
              next_cursor: null,
              as_of: '2026-11-02T08:00:00Z',
            });
      const other = others[id];
      return other ? Promise.resolve(other()) : Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/kooperation']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-18 Basis: Mitgliedschaften und Rollen des aktiven Mandanten', () => {
  it('ui18_zeigt_nur_aktiven_mandanten_und_rechtezustand', async () => {
    const { calls, container } = setup([
      M({}),
      M({ id: '00000000-0000-4000-8000-0000000000e2', subject: 'user-4712', role_code: 'support' }),
    ]);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Kooperation und Rollen' }),
    ).toBeInTheDocument();
    // Kontext: nur der aktive Mandant; der andere wählbare Mandant des Kontos erscheint nicht als Inhalt
    const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
    expect(ctx).toHaveTextContent('Verkehrsbetrieb Nord');
    expect(ctx).toHaveTextContent('Datenstand');
    const table = await screen.findByRole('table', {
      name: 'Mitgliedschaften des aktiven Mandanten',
    });
    expect(within(table).getAllByRole('row')).toHaveLength(3); // Kopf + 2 Zeilen
    // der Mandantenwähler im Kopf nennt die wählbaren Mandanten des Kontos; Inhalte des Bildschirms stammen nur vom aktiven Mandanten
    expect(within(screen.getByRole('main')).queryByText(/Fremdbetrieb/)).toBeNull();
    // die Anfrage trägt keinen Mandanten: der Server bestimmt ihn aus der Mitgliedschaft
    const list = calls.find((c) => c.id === 'listMemberships');
    expect(JSON.stringify(list?.args)).not.toMatch(/tenant/i);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ui18_rechtezustand_ohne_berechtigung_zeigt_keine_daten', async () => {
    setup(new ApiError(403));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByText('user-4711')).toBeNull();
  });

  it('Leerzustand und Fehlerzustand', async () => {
    const empty = setup([]);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'empty'),
    );
    empty.unmount();
    setup(new ApiError(503));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'unavailable'),
    );
  });

  it('ohne Recht auf den Bereich zeigt der Direktaufruf den Rechtezustand und lädt nichts', async () => {
    const { calls } = setup([M({})], ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(calls.some((c) => c.id === 'listMemberships')).toBe(false);
  });

  it('Entziehen sendet die gelesene Revision und lädt neu', async () => {
    const { calls } = setup([M({})], ['tenant_admin'], { revokeMembership: () => undefined });
    fireEvent.click(
      await screen.findByRole('button', { name: /Entziehen \(user-4711, dispatcher\)/ }),
    );
    await waitFor(() => expect(calls.some((c) => c.id === 'revokeMembership')).toBe(true));
    const revoke = calls.find((c) => c.id === 'revokeMembership');
    expect(revoke?.args).toMatchObject({
      path: { membership_id: '00000000-0000-4000-8000-0000000000e1' },
      revision: 3,
    });
    await waitFor(() => expect(calls.filter((c) => c.id === 'listMemberships').length).toBe(2));
    expect(await screen.findByRole('status')).toHaveTextContent('wurde entzogen');
  });

  it('Erteilen sendet Issuer, Subject und Rolle, aber keinen Mandanten', async () => {
    const { calls } = setup([], ['tenant_admin'], { createMembership: () => M({}) });
    fireEvent.change(await screen.findByLabelText('Konto (Subject)'), {
      target: { value: ' user-9 ' },
    });
    fireEvent.change(screen.getByLabelText('Rollencode'), { target: { value: 'support' } });
    fireEvent.click(screen.getByRole('button', { name: 'Erteilen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createMembership')).toBe(true));
    const body = calls.find((c) => c.id === 'createMembership')?.args['body'];
    expect(body).toEqual({
      issuer: 'https://idp.example/realms/test',
      subject: 'user-9',
      role_code: 'support',
    });
  });
});
