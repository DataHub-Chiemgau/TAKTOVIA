import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { RetentionOverview } from './RetentionOverview';

const RULES = {
  items: [
    {
      id: '00000000-0000-4000-8000-0000000000a1',
      data_category: 'LK_PII',
      retention_days: 30,
      action: 'ANONYMIZE',
      status: 'APPROVED',
      revision: 2,
    },
    {
      id: '00000000-0000-4000-8000-0000000000a2',
      data_category: 'LK_STANDORT',
      retention_days: 7,
      action: 'DELETE',
      status: 'DRAFT',
      revision: 1,
    },
  ],
  next_cursor: null,
  as_of: '2026-11-11T12:00:00Z',
};
const JOURNAL = {
  items: [
    {
      id: '00000000-0000-4000-8000-0000000000b1',
      job_id: '00000000-0000-4000-8000-0000000000c1',
      data_category: 'LK_PII',
      object_ref: 'passenger:00000000-0000-4000-8000-0000000000d1',
      action: 'ANONYMIZE',
      applied_at: '2026-11-11T11:00:00Z',
    },
  ],
  next_cursor: null,
  as_of: '2026-11-11T12:00:00Z',
};

function setup(fail?: ApiError) {
  const calls: OperationId[] = [];
  const client = {
    call: vi.fn((id: OperationId) => {
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['tenant_admin'] }));
      calls.push(id);
      if (fail) return Promise.reject(fail);
      return Promise.resolve(id === 'listRetentionRules' ? RULES : JOURNAL);
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <RetentionOverview />
    </SessionProvider>,
  );
  return { ...view, calls };
}

describe('UI-20 Basis: Aufbewahrungsregeln und Löschprotokoll', () => {
  it('regeluebersicht_zeigt_freigabestatus_als_text_und_das_loeschprotokoll_ohne_personendaten', async () => {
    const { container, calls } = setup();
    const rules = await screen.findByRole('table', { name: 'Aufbewahrungsregeln' });
    expect(within(rules).getByText('Freigegeben')).toBeInTheDocument();
    expect(within(rules).getByText('Entwurf')).toBeInTheDocument();
    const journal = screen.getByRole('table', { name: /Löschprotokoll/ });
    expect(
      within(journal).getByText('passenger:00000000-0000-4000-8000-0000000000d1'),
    ).toBeInTheDocument();
    expect(calls.sort()).toEqual(['listErasureJournal', 'listRetentionRules']);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ohne_berechtigung_erscheinen_keine_daten', async () => {
    setup(new ApiError(403));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
