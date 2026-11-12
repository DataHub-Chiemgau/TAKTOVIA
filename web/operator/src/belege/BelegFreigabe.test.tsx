import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Expense, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { BelegFreigabe } from './BelegFreigabe';

const DOC = '00000000-0000-4000-8000-0000000000e1';

const step = (s: 'CAPTURE' | 'REVIEW' | 'BUDGET', by: string, n: number) => ({
  id: `00000000-0000-4000-8000-00000000010${n}`,
  step: s,
  document_revision: 5,
  decision: 'APPROVED' as const,
  decided_by: by,
  reason: 'Begründung',
  decided_at: '2026-06-15T08:00:00Z',
});

const EXPENSE: Expense = {
  document_id: DOC,
  legal_entity_id: '00000000-0000-4000-8000-0000000000a1',
  state: 'REVIEWED',
  source_channel: 'DRIVER_APP',
  document_kind: 'FUEL_RECEIPT',
  case_ref: null,
  mime: 'application/pdf',
  size_bytes: 1000,
  sha256: 'a'.repeat(43),
  received_at: '2026-06-15T07:00:00Z',
  scanned_at: '2026-06-15T07:01:00Z',
  sender_verified: null,
  integrity: 'VERIFIED',
  approvable: true,
  blockers: [],
  unconfirmed_fields: [],
  sum_mismatch: false,
  cost_objects: [],
  fields: {},
  approval_steps: [step('CAPTURE', 'fahrer-1', 1), step('REVIEW', 'pruefer-1', 2)],
  export_state: 'NOT_EXPORTED',
  revision: 5,
};

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };

/** Rendert die Freigabe für einen Beleg; `scopes` und `subject` bestimmen die Sitzung (nur diese Scopes machen Schaltflächen aktiv). */
function setup(subject: string, scopes: string[], handlers: Handlers, expense: Expense = EXPENSE) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ subject, roles: ['finance'], scopes }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <BelegFreigabe expense={expense} />
    </SessionProvider>,
  );
  return { ...view, calls };
}

const problem = (status: number, code: string) =>
  new ApiError(status, {
    type: 'about:blank',
    title: code,
    status,
    code,
    correlation_id: '00000000-0000-4000-8000-0000000000ff',
  });

describe('UI-14 Basis: Belegfreigabe', () => {
  it('freigeben_nur_mit_recht_aktiv_und_403_ohne_datenpreisgabe', async () => {
    // ohne Freigaberecht (nur Erfassung/Prüfung): die Schaltflächen der Freigabe sind deaktiviert
    const denied = setup('pruefer-2', ['receipts:read', 'receipts:write', 'expenses:review'], {});
    expect(await screen.findByRole('heading', { name: 'Belegfreigabe' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Begründung'), { target: { value: 'Freigegeben' } });
    expect(screen.getByRole('button', { name: 'Freigeben' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ablehnen' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Prüfung bestanden' })).toBeDisabled(); // Beleg ist nicht in der Prüfung
    denied.unmount();

    // mit Recht, aber der Erfasser selbst: keine Selbstfreigabe
    const self = setup('fahrer-1', ['expenses:approve'], {});
    fireEvent.change(await screen.findByLabelText('Begründung'), {
      target: { value: 'Freigegeben' },
    });
    expect(screen.getByRole('button', { name: 'Freigeben' })).toBeDisabled();
    self.unmount();

    // mit Recht und einer anderen Person: aktiv, bindet sich an die exakte Revision; der Server-403 zeigt eine feste Meldung ohne Belegdaten
    const { calls } = setup('freigeber-1', ['expenses:approve'], {
      approveExpense: () => problem(403, 'FORBIDDEN'),
    });
    fireEvent.change(await screen.findByLabelText('Begründung'), {
      target: { value: 'Freigegeben' },
    });
    const approve = screen.getByRole('button', { name: 'Freigeben' });
    expect(approve).toBeEnabled();
    fireEvent.click(approve);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'Dafür fehlt das Recht, oder die Freigabe muss durch eine andere Person erfolgen (403).',
    );
    expect(alert.textContent ?? '').not.toContain(DOC); // keine Datenpreisgabe
    expect(calls.find((c) => c.id === 'approveExpense')?.args).toMatchObject({
      path: { expense_id: DOC },
      revision: 5,
      body: { reason: 'Freigegeben', validated_document_revision: 5 },
    });
    expect(JSON.stringify(calls.find((c) => c.id === 'approveExpense')?.args)).not.toMatch(
      /tenant_id/,
    );
  });

  it('zeigt_schritte_blockaden_mit_eigenem_text_und_die_zweite_freigabe', async () => {
    const { calls, container } = setup('freigeber-1', ['expenses:approve'], {
      approveExpense: () => ({
        expense_id: DOC,
        revision: 5,
        state: 'WAITING_SECOND_APPROVAL',
        posting_id: null,
      }),
    });
    const steps = await screen.findByRole('list', { name: 'Freigabeschritte' });
    expect(steps).toHaveTextContent('Erfassung: bestätigt von fahrer-1');
    expect(steps).toHaveTextContent('Fachliche Prüfung: bestätigt von pruefer-1');
    fireEvent.change(screen.getByLabelText('Begründung'), { target: { value: 'Freigegeben' } });
    fireEvent.click(screen.getByRole('button', { name: 'Freigeben' }));
    expect(await screen.findByText(/eine zweite Person muss freigeben/)).toBeInTheDocument();
    expect(screen.getByText('wartet auf zweite Freigabe')).toBeInTheDocument();
    expect(calls.filter((c) => c.id === 'approveExpense')).toHaveLength(1);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('eigener_text_je_blockade', async () => {
    const { calls } = setup('freigeber-1', ['expenses:approve'], {
      approveExpense: () => problem(409, 'DUPLICATE_REVIEW_REQUIRED'),
      getExpense: () => ({ ...EXPENSE, revision: 6 }),
    });
    fireEvent.change(await screen.findByLabelText('Begründung'), {
      target: { value: 'Freigegeben' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Freigeben' }));
    expect(await screen.findByText(/Dublettentreffer ist offen/)).toBeInTheDocument();
    expect(calls.some((c) => c.id === 'reviewExpense')).toBe(false);
  });
});
