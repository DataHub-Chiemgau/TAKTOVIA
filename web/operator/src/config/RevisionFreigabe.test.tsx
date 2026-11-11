import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  ConfigImpactReport,
  ConfigRevision,
  ConfigValidationReport,
  OperationId,
  Problem,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { RevisionFreigabe } from './RevisionFreigabe';

const TENANT = '00000000-0000-4000-8000-000000000001';
const REV_ID = '00000000-0000-4000-8000-0000000000c2';

const DRAFT: ConfigRevision = {
  id: REV_ID,
  scope_kind: 'TENANT',
  scope_id: TENANT,
  revision: 2,
  state: 'DRAFT',
  row_version: 1,
  schema_version: '1',
  values: { max_wait_min: 25 },
  effective_from: '2026-12-01T00:00:00Z',
  effective_to: null,
  created_by: 'user:alice',
  approved_by: null,
  reason: 'Wartezeit angepasst',
  created_at: '2026-11-02T09:00:00Z',
  published_at: null,
};

const REPORT: ConfigImpactReport = {
  id: '00000000-0000-4000-8000-0000000000d1',
  revision_id: REV_ID,
  revision_row_version: 3,
  values_hash: 'ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12',
  generated_at: '2026-11-02T10:00:00Z',
  created_by: 'user:bob',
  highest_severity: 'NONE',
  conflict_count: 0,
  affected: [],
};

const problem = (status: number, code: string, violations?: Problem['violations']): ApiError =>
  new ApiError(status, {
    type: 'about:blank',
    title: code,
    status,
    code,
    correlation_id: '00000000-0000-4000-8000-0000000000ff',
    ...(violations ? { violations } : {}),
  });

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };

/** Rendert die Freigabe-Ansicht für einen Entwurf; `subject` ist die angemeldete Person (Ersteller ist `user:alice`). */
function setup(subject: string, handlers: Handlers, revision: ConfigRevision = DRAFT) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ subject, roles: ['tenant_admin'] }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <RevisionFreigabe revision={revision} />
    </SessionProvider>,
  );
  return { ...view, calls };
}

const VALID: ConfigValidationReport = {
  revision_id: REV_ID,
  valid: true,
  state: 'VALIDATED',
  row_version: 2,
  violations: [],
};

describe('UI-17 Basis: Revision freigeben', () => {
  it('ui17_zeigt_validator_ergebnis_pruefer_und_wirksamkeitsdatum_und_fuehrt_den_ablauf_aus', async () => {
    const { calls, container } = setup('bob', {
      validateConfigRevision: () => VALID,
      approveConfigRevision: () => ({
        ...DRAFT,
        state: 'APPROVED',
        row_version: 3,
        approved_by: 'user:bob',
      }),
      requestConfigImpact: () => REPORT,
      publishConfig: () => ({
        resource_id: REV_ID,
        revision: 4,
        state: 'PUBLISHED',
        effective_from: DRAFT.effective_from,
        audit_event_id: '00000000-0000-4000-8000-0000000000a1',
      }),
    });
    expect(
      await screen.findByRole('heading', { name: 'Revision 2 freigeben' }),
    ).toBeInTheDocument();
    expect(screen.getByText('user:alice')).toBeInTheDocument(); // Ersteller
    expect(screen.getByText('noch nicht freigegeben')).toBeInTheDocument(); // Prüfer
    expect(screen.getByText(/ab 2026-12-01/)).toBeInTheDocument(); // Wirksamkeitsdatum
    expect(screen.getByRole('button', { name: 'Veröffentlichen' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Freigeben (zweite Person)' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Prüfen' }));
    expect(await screen.findByText('gültig')).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'validateConfigRevision')?.args).toMatchObject({
      path: { revision_id: REV_ID },
      revision: 1,
    });
    // Bob ist nicht der Ersteller: Freigabe möglich, Prüfer wird angezeigt
    fireEvent.click(screen.getByRole('button', { name: 'Freigeben (zweite Person)' }));
    expect(await screen.findByText('Freigegeben von user:bob.')).toBeInTheDocument();
    expect(screen.getByText('freigegeben')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Veröffentlichen' })).toBeDisabled(); // Wirkungsbericht fehlt
    fireEvent.click(screen.getByRole('button', { name: 'Wirkung prüfen' }));
    expect(await screen.findByText(/keine Konflikte/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Veröffentlichen' }));
    expect(await screen.findByText(/Veröffentlicht, wirksam ab/)).toBeInTheDocument();
    const publish = calls.find((c) => c.id === 'publishConfig');
    expect(publish?.args).toMatchObject({
      path: { revision_id: REV_ID },
      revision: 3,
      body: { effective_from: DRAFT.effective_from, impact_report_id: REPORT.id },
    });
    expect(JSON.stringify(publish?.args)).not.toMatch(/tenant_id/);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('ui17_ersteller_kann_nicht_selbst_freigeben', async () => {
    setup('alice', { validateConfigRevision: () => VALID });
    fireEvent.click(await screen.findByRole('button', { name: 'Prüfen' }));
    expect(await screen.findByText('gültig')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Freigeben (zweite Person)' })).toBeDisabled();
    expect(screen.getByText(/muss eine zweite Person vornehmen/)).toBeInTheDocument();
  });

  it('ui17_blockiert_unzulaessigen_sicherheitswert_und_zeigt_412_konflikt', async () => {
    // Unzulässiger Sicherheitswert: Prüfung ungültig -> Freigabe und Veröffentlichung blockiert (der Server prüft zusätzlich selbst).
    const unsafe: ConfigValidationReport = {
      revision_id: REV_ID,
      valid: false,
      state: 'DRAFT',
      row_version: 1,
      violations: [
        {
          pointer: '/values/vehicle_readiness_require_checks',
          code: 'SAFETY_INVARIANT_VIOLATED',
          message: 'Sicherheitsprüfungen dürfen nicht abgeschaltet werden',
        },
      ],
    };
    const first = setup('bob', { validateConfigRevision: () => unsafe });
    fireEvent.click(await screen.findByRole('button', { name: 'Prüfen' }));
    expect(await screen.findByText(/Unzulässiger Sicherheitswert/)).toBeInTheDocument();
    expect(screen.getByText('ungültig')).toBeInTheDocument();
    expect(screen.getByText(/vehicle_readiness_require_checks/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Freigeben (zweite Person)' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Veröffentlichen' })).toBeDisabled();
    expect(first.calls.some((c) => c.id === 'publishConfig')).toBe(false);
    expect(formatViolations(await runAxe(first.container))).toBe('');
    first.unmount();

    // 412-Konflikt bei der Veröffentlichung: Hinweis und „Aktuellen Stand laden“ lädt die Revision neu.
    const approved: ConfigRevision = {
      ...DRAFT,
      state: 'APPROVED',
      row_version: 3,
      approved_by: 'user:bob',
    };
    const second = setup(
      'carol',
      {
        requestConfigImpact: () => REPORT,
        publishConfig: () => problem(412, 'REVISION_MISMATCH'),
        getConfigRevision: () => ({ ...approved, row_version: 5, reason: 'Inzwischen geändert' }),
      },
      approved,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Wirkung prüfen' }));
    await screen.findByText(/keine Konflikte/);
    fireEvent.click(screen.getByRole('button', { name: 'Veröffentlichen' }));
    const alert = await screen.findByText(/Konflikt \(412\)/);
    expect(alert).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Aktuellen Stand laden' }));
    await waitFor(() => expect(screen.getByText('Inzwischen geändert')).toBeInTheDocument());
    expect(second.calls.some((c) => c.id === 'getConfigRevision')).toBe(true);
    // nach dem Neuladen ist der Bericht verworfen: erneute Wirkungsprüfung nötig
    expect(screen.getByRole('button', { name: 'Veröffentlichen' })).toBeDisabled();
  });

  it('ui17_zeigt_konkurrierende_version_fehlendes_recht_und_pruefverstoss_bei_der_veroeffentlichung', async () => {
    const approved: ConfigRevision = {
      ...DRAFT,
      state: 'APPROVED',
      row_version: 3,
      approved_by: 'user:bob',
    };
    const conflict = setup(
      'carol',
      { requestConfigImpact: () => REPORT, publishConfig: () => problem(409, 'CONFLICT') },
      approved,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Wirkung prüfen' }));
    await screen.findByText(/keine Konflikte/);
    fireEvent.click(screen.getByRole('button', { name: 'Veröffentlichen' }));
    expect(await screen.findByText(/Konkurrierende Version/)).toBeInTheDocument();
    conflict.unmount();

    const forbidden = setup(
      'carol',
      { requestConfigImpact: () => problem(403, 'FORBIDDEN') },
      approved,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Wirkung prüfen' }));
    expect(
      await screen.findByText(/Recht, oder die Freigabe muss durch eine zweite Person/),
    ).toBeInTheDocument();
    forbidden.unmount();

    setup(
      'carol',
      {
        requestConfigImpact: () => REPORT,
        publishConfig: () =>
          problem(422, 'VALIDATION_FAILED', [
            {
              pointer: '/impact_report_id',
              code: 'INVALID_VALUE',
              message: 'Wirkungsbericht unbekannt',
            },
          ]),
      },
      approved,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Wirkung prüfen' }));
    await screen.findByText(/keine Konflikte/);
    fireEvent.click(screen.getByRole('button', { name: 'Veröffentlichen' }));
    expect(await screen.findByText(/Veröffentlichung ist blockiert/)).toBeInTheDocument();
    expect(screen.getByText(/Wirkungsbericht unbekannt/)).toBeInTheDocument();
  });
});
