import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import type { ApiClient, AuditEventPage, OperationId } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { AuditList } from './AuditList';

const PAGE: AuditEventPage = {
  items: [
    {
      id: '00000000-0000-4000-8000-0000000000a1',
      actor_ref: 'user:11111111-1111-4111-8111-111111111111',
      action: 'identity.membership.granted',
      object_ref: 'membership:22222222-2222-4222-8222-222222222222',
      occurred_at: '2026-03-01T10:00:00Z',
      correlation_id: '00000000-0000-4000-8000-0000000000c1',
    },
  ],
  next_cursor: null,
  as_of: '2026-11-11T12:00:00Z',
};

function setup(result: AuditEventPage | ApiError) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['tenant_admin'] }));
      calls.push({ id, args });
      return result instanceof ApiError ? Promise.reject(result) : Promise.resolve(result);
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <AuditList />
    </SessionProvider>,
  );
  return { ...view, calls };
}

describe('UI-20 Basis: Auditliste mit Zweckangabe', () => {
  it('auditliste_ohne_zweck_sendet_keine_anfrage_und_zeigt_den_zustand', async () => {
    const { calls } = setup(PAGE);
    fireEvent.click(screen.getByRole('button', { name: 'Abfragen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Zweck fehlt');
    expect(calls).toHaveLength(0);
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('auditliste_zeigt_akteur_zeitpunkt_aktivitaet_und_sendet_den_zweck_ohne_mandant', async () => {
    const { calls, container } = setup(PAGE);
    fireEvent.change(screen.getByLabelText('Zweck der Abfrage'), {
      target: { value: 'security_review' },
    });
    fireEvent.change(screen.getByLabelText('Akteur'), { target: { value: ' user:x ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Abfragen' }));
    const table = await screen.findByRole('table', { name: /Auditeinträge/ });
    const row = within(table).getByText('identity.membership.granted').closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('2026-03-01T10:00:00Z');
    expect(row).toHaveTextContent('user:11111111');
    expect(row).toHaveTextContent('membership:2222');
    expect(calls[0]?.args).toMatchObject({
      query: { purpose: 'security_review', actor_ref: 'user:x' },
    });
    expect(JSON.stringify(calls[0]?.args)).not.toMatch(/tenant/i);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('auditliste_zeigt_rechtezustand_und_abgelaufenen_zugriff_ohne_daten', async () => {
    const forbidden = setup(new ApiError(403));
    fireEvent.change(screen.getByLabelText('Zweck der Abfrage'), {
      target: { value: 'support_case' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Abfragen' }));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('table')).toBeNull();
    forbidden.unmount();

    setup(new ApiError(401));
    fireEvent.change(screen.getByLabelText('Zweck der Abfrage'), {
      target: { value: 'support_case' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Abfragen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Zugriff ist abgelaufen');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('auditliste_leerzustand', async () => {
    setup({ ...PAGE, items: [] });
    fireEvent.change(screen.getByLabelText('Zweck der Abfrage'), {
      target: { value: 'compliance_check' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Abfragen' }));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'empty'),
    );
  });
});
