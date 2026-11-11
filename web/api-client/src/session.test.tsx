import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from './runtime';
import type { ApiClient } from './runtime';
import { SessionProvider, useSession } from './session';
import type { Me } from './generated';

const ME: Me = {
  subject: 's',
  issuer: 'i',
  tenant_id: '00000000-0000-4000-8000-000000000001',
  tenant_name: 'Verkehrsbetrieb Nord',
  roles: ['ADMIN'],
  service_scope: null,
  session_id: '00000000-0000-4000-8000-000000000002',
  mfa: true,
  tenants: [],
};

function Probe() {
  const s = useSession();
  return (
    <p data-testid="p">
      {s.status}:
      {s.status === 'ready' ? s.me.tenant_name : s.status === 'error' ? s.error.status : ''}
    </p>
  );
}

describe('Sitzung aus /v1/me', () => {
  it('lädt /v1/me genau einmal und stellt Mandant, Rollen und Ladezeit bereit', async () => {
    let calls = 0;
    const client = {
      call: (id: string) => {
        calls += 1;
        expect(id).toBe('getMe');
        return Promise.resolve(ME);
      },
    } as unknown as ApiClient;
    render(
      <SessionProvider client={client} now={() => 1_700_000_000_000}>
        <Probe />
      </SessionProvider>,
    );
    expect(screen.getByTestId('p')).toHaveTextContent('loading:');
    await waitFor(() =>
      expect(screen.getByTestId('p')).toHaveTextContent('ready:Verkehrsbetrieb Nord'),
    );
    expect(calls).toBe(1);
  });

  it.each([401, 403, 404, 429, 0])(
    'Fehlerstatus %i wird als Fehlerzustand mit Status geliefert',
    async (status) => {
      const client = { call: () => Promise.reject(new ApiError(status)) } as unknown as ApiClient;
      render(
        <SessionProvider client={client}>
          <Probe />
        </SessionProvider>,
      );
      await waitFor(() => expect(screen.getByTestId('p')).toHaveTextContent(`error:${status}`));
    },
  );
});
