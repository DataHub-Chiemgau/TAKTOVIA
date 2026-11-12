import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, ChecklistTemplateRevision, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const DRAFT: ChecklistTemplateRevision = {
  id: '00000000-0000-4000-8000-0000000000c1',
  template_id: '00000000-0000-4000-8000-0000000000c2',
  version: 1,
  name: 'Fahrzeugtechnik',
  vehicle_class: 'PKW',
  service_mode: 'ON_DEMAND',
  state: 'DRAFT',
  steps: [],
  created_by: 'user:a',
  approved_by: null,
  published_at: null,
  revision: 1,
  created_at: '2026-11-01T08:00:00Z',
};

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(
  handler: (id: OperationId, args: Record<string, unknown>) => unknown,
  scopes: string[],
) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['workshop'], scopes }));
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/checklisten']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: ChecklistTemplateRevision[]) => ({
  items,
  next_cursor: null,
  as_of: '2026-11-11T08:00:00Z',
});

describe('UI-11 Vorlageneditor', () => {
  it('zeigt den Leerzustand und legt einen Entwurf mit Prüfelement an; Feldfehler (422) erscheinen am Feld', async () => {
    const { calls } = setup(
      (id) => {
        if (id === 'listChecklistTemplateRevisions') return page([]);
        if (id === 'createChecklistTemplate')
          throw new ApiError(422, {
            type: 'x',
            title: 'x',
            status: 422,
            code: 'VALIDATION_FAILED',
            correlation_id: 'c',
            violations: [{ pointer: '/name', code: 'REQUIRED', message: 'm' }],
          });
        return undefined;
      },
      ['me:read', 'checklists:read', 'checklists:write'],
    );
    expect(
      await screen.findByText('Noch keine Checklistenvorlagen in diesem Mandanten'),
    ).toBeVisible();
    fireEvent.change(screen.getByLabelText('Schlüssel des Prüfelements'), {
      target: { value: 'LIGHTS' },
    });
    fireEvent.change(screen.getByLabelText('Titel'), { target: { value: 'Beleuchtung' } });
    fireEvent.click(screen.getByLabelText(/Sicherheitskritisch/));
    fireEvent.click(screen.getByRole('button', { name: 'Prüfelement übernehmen' }));
    expect(screen.getByText('Prüfelemente im Entwurf: 1')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Entwurf anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createChecklistTemplate')).toBe(true));
    const body = calls.find((c) => c.id === 'createChecklistTemplate')?.args['body'] as {
      steps: { step_code: string; safety_critical: boolean; input_kind: string }[];
    };
    expect(body.steps).toMatchObject([
      { step_code: 'LIGHTS', safety_critical: true, input_kind: 'OK_DEFECT_NA' },
    ]);
    expect(screen.queryByText(/wurde angelegt/)).toBeNull();
    expect(
      await screen.findAllByText(/./, {
        selector: '[role="alert"], .tk-field-error, [id$="-error"]',
      }),
    ).not.toHaveLength(0);
  });

  it('gibt einen Entwurf mit der gelesenen Revision frei', async () => {
    const { calls, container } = setup(
      (id) => {
        if (id === 'listChecklistTemplateRevisions') return page([DRAFT]);
        if (id === 'publishChecklistTemplateRevision')
          return { ...DRAFT, state: 'PUBLISHED', revision: 2 };
        return undefined;
      },
      ['me:read', 'checklists:read', 'checklists:write'],
    );
    const table = await screen.findByRole('table', { name: 'Revisionen der Checklistenvorlagen' });
    expect(within(table).getByText('Entwurf')).toBeVisible();
    fireEvent.click(within(table).getByRole('button', { name: /Freigeben Fahrzeugtechnik/ }));
    await waitFor(() =>
      expect(calls.some((c) => c.id === 'publishChecklistTemplateRevision')).toBe(true),
    );
    expect(calls.find((c) => c.id === 'publishChecklistTemplateRevision')?.args).toMatchObject({
      path: { checklist_revision_id: DRAFT.id },
      revision: 1,
    });
    expect(await screen.findByText(/unveränderlich/)).toBeVisible();
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('blendet Schreibaktionen ohne Schreibrecht aus', async () => {
    setup(
      (id) => (id === 'listChecklistTemplateRevisions' ? page([DRAFT]) : undefined),
      ['me:read', 'checklists:read'],
    );
    const table = await screen.findByRole('table', { name: 'Revisionen der Checklistenvorlagen' });
    expect(within(table).queryByRole('button')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Neue Vorlage' })).toBeNull();
  });
});
