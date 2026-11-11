import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, CompanyProfile, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const P = (over: Partial<CompanyProfile> = {}): CompanyProfile => ({
  legal_name: 'Stadtwerke GmbH',
  contact_email: 'info@example.org',
  default_currency: 'EUR',
  revision: 4,
  ...over,
});

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(
  profile: CompanyProfile | ApiError,
  roles = ['tenant_admin'],
  update?: () => unknown,
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
              ? ['me:read', 'memberships:read', 'company:read', 'company:write']
              : ['me:read'],
          }),
        );
      if (id === 'getCompanyProfile')
        return profile instanceof ApiError ? Promise.reject(profile) : Promise.resolve(profile);
      if (id === 'updateCompanyProfile' && update) return Promise.resolve(update());
      return Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/unternehmensprofil']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-18 Basis: Unternehmensprofil des Mandanten', () => {
  it('zeigt das Profil im Formular und ist barrierefrei', async () => {
    const { container } = setup(P());
    expect(await screen.findByLabelText('Kontakt-E-Mail')).toHaveValue('info@example.org');
    expect(screen.getByLabelText('Standardwährung')).toHaveValue('EUR');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('Speichern sendet die gelesene Revision und keinen Mandanten, danach wird neu geladen', async () => {
    const { calls } = setup(P(), ['tenant_admin'], () => P({ revision: 5 }));
    fireEvent.change(await screen.findByLabelText('Kontakt-E-Mail'), {
      target: { value: 'neu@example.org' },
    });
    fireEvent.change(screen.getByLabelText('Standardwährung'), { target: { value: 'CHF' } });
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'updateCompanyProfile')).toBe(true));
    const update = calls.find((c) => c.id === 'updateCompanyProfile');
    expect(update?.args).toMatchObject({
      revision: 4,
      body: { contact_email: 'neu@example.org', default_currency: 'CHF' },
    });
    expect(JSON.stringify(update?.args)).not.toMatch(/tenant/i);
    await waitFor(() => expect(calls.filter((c) => c.id === 'getCompanyProfile').length).toBe(2));
    expect(await screen.findByRole('status')).toHaveTextContent('gespeichert');
  });

  it('412-Konflikt lädt neu statt zu überschreiben', async () => {
    const { calls } = setup(P(), ['tenant_admin'], () => {
      throw new ApiError(412);
    });
    fireEvent.change(await screen.findByLabelText('Kontakt-E-Mail'), {
      target: { value: 'x@example.org' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Konflikt');
    await waitFor(() => expect(calls.filter((c) => c.id === 'getCompanyProfile').length).toBe(2));
  });

  it('rechtezustand: ohne Recht keine Daten und kein Laden', async () => {
    const { calls } = setup(P(), ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(calls.some((c) => c.id === 'getCompanyProfile')).toBe(false);
  });

  it('403 der Antwort zeigt den Rechtezustand ohne Daten', async () => {
    setup(new ApiError(403));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByLabelText('Kontakt-E-Mail')).toBeNull();
  });
});
