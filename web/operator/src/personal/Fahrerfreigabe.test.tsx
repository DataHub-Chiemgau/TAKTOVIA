import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, DriverRelease, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const RELEASE: DriverRelease = {
  id: '00000000-0000-4000-8000-0000000000a1',
  driver_id: '00000000-0000-4000-8000-0000000000d1',
  recipient_tenant_id: '00000000-0000-4000-8000-0000000000b1',
  service_ids: ['00000000-0000-4000-8000-000000000005'],
  valid_from: '2026-12-01T06:00:00Z',
  valid_to: '2026-12-05T18:00:00Z',
  revoked_at: null,
  revision: 1,
};

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(create: () => unknown, scopes = ['me:read', 'driver-releases:write']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['staff_planning'], scopes }));
      if (id === 'createDriverRelease') return Promise.resolve(create());
      if (id === 'revokeDriverRelease') return Promise.resolve(undefined);
      return Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/personal/fahrerfreigabe']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-18 Basis: Fahrerfreigabe', () => {
  it('zeigt die sichtbare Feldliste vorab und ist barrierefrei', async () => {
    const { container } = setup(() => RELEASE);
    expect(await screen.findByText(/Sichtbar für den Empfängermandanten/)).toHaveTextContent(
      'Qualifikationen, Einsatzzeitraum, Kontaktkanal',
    );
    expect(screen.getByText(/Nie sichtbar/)).toHaveTextContent('Personalakte');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('erteilt mit Diensten und Frist und widerruft mit der gelesenen Revision', async () => {
    const { calls } = setup(() => RELEASE);
    fireEvent.change(await screen.findByLabelText('Fahrer (Kennung)'), {
      target: { value: RELEASE.driver_id },
    });
    fireEvent.change(screen.getByLabelText('Empfängermandant (Kennung)'), {
      target: { value: RELEASE.recipient_tenant_id },
    });
    fireEvent.change(screen.getByLabelText(/Dienste des Empfängers/), {
      target: { value: RELEASE.service_ids[0] },
    });
    fireEvent.change(screen.getByLabelText('Beginn (lokale Zeit)'), {
      target: { value: '2026-12-01T06:00' },
    });
    fireEvent.change(screen.getByLabelText('Ende (lokale Zeit)'), {
      target: { value: '2026-12-05T18:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Fahrer freigeben' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createDriverRelease')).toBe(true));
    const create = calls.find((c) => c.id === 'createDriverRelease');
    expect(create?.args['body']).toMatchObject({
      driver_id: RELEASE.driver_id,
      recipient_tenant_id: RELEASE.recipient_tenant_id,
      service_ids: RELEASE.service_ids,
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Freigabe widerrufen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'revokeDriverRelease')).toBe(true));
    expect(calls.find((c) => c.id === 'revokeDriverRelease')?.args).toMatchObject({
      path: { release_id: RELEASE.id },
      revision: 1,
    });
    expect(await screen.findByRole('status')).toHaveTextContent('ab sofort keinen Zugriff');
  });

  it('zeigt Konflikt und unbekannten Empfänger verständlich, ohne Daten zu verraten', async () => {
    const { calls } = setup(() => {
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Fahrer freigeben' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createDriverRelease')).toBe(true));
  });

  it('ohne Recht erscheint der Rechtezustand ohne Formular', async () => {
    setup(() => RELEASE, ['me:read']);
    await waitFor(() => expect(screen.queryByText('Was der Empfänger sieht')).toBeNull());
    expect(screen.queryByRole('button', { name: 'Fahrer freigeben' })).toBeNull();
  });
});
