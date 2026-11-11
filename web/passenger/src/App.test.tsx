import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import { fakeApiClient, sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from './App';

function setup(roles: string[] | ApiError, path = '/') {
  const client = fakeApiClient({
    getMe: () => (roles instanceof ApiError ? roles : sampleMe({ roles })),
  });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider client={client} now={() => Date.parse('2026-10-25T09:00:00+01:00')}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('passenger Anwendungsrahmen', () => {
  it('Fahrgast sieht Start, Meine Reisen und Konto; Kontext benennt Mandant, Status und Datenstand', async () => {
    const { container } = setup(['PASSENGER']);
    expect(await screen.findByRole('heading', { level: 1, name: 'Start' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(
      within(nav)
        .getAllByRole('link')
        .map((a) => a.textContent),
    ).toEqual(['Start', 'Meine Reisen', 'Konto']);
    const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
    expect(ctx).toHaveTextContent('Verkehrsbetrieb Nord');
    expect(ctx).toHaveTextContent('Datenstand');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('Konto (UI-05 Basis) zeigt die eigenen Sitzungen und „Alle Geräte abmelden“', async () => {
    const client = fakeApiClient({
      getMe: () => sampleMe({ roles: ['PASSENGER'] }),
      listMySessions: () => ({
        items: [
          {
            id: '00000000-0000-4000-8000-000000000002',
            subject: 'user-1',
            created_at: '2026-10-25T07:00:00Z',
            last_seen_at: '2026-10-25T08:00:00Z',
            revoked_at: null,
            revision: 1,
          },
        ],
        next_cursor: null,
        as_of: '2026-10-25T08:00:00Z',
      }),
    });
    render(
      <MemoryRouter initialEntries={['/konto']}>
        <SessionProvider client={client} now={() => Date.parse('2026-10-25T09:00:00+01:00')}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'Konto' })).toBeInTheDocument();
    expect(
      await screen.findByRole('table', { name: 'Meine Sitzungen im aktiven Mandanten' }),
    ).toHaveTextContent('dieses Gerät');
    expect(screen.getByRole('button', { name: 'Alle Geräte abmelden' })).toBeInTheDocument();
  });

  it('ohne Fahrgastrecht ist „Meine Reisen“ nicht in der Navigation und per Direktaufruf gesperrt', async () => {
    setup(['ADMIN'], '/reisen');
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(within(nav).queryByRole('link', { name: 'Meine Reisen' })).toBeNull();
  });

  it.each([
    [401, 'unauthenticated'],
    [429, 'rateLimited'],
    [503, 'unavailable'],
  ])('/v1/me mit Status %i zeigt den Zustand %s', async (status, kind) => {
    setup(new ApiError(status));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', kind),
    );
  });

  it('unbekannte Adresse zeigt „Nicht gefunden“', async () => {
    setup(['PASSENGER'], '/nirgends');
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'notFound'),
    );
  });
});
