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

  it('Konto (UI-05 Basis) zeigt den Leerzustand', async () => {
    setup(['PASSENGER'], '/konto');
    expect(await screen.findByRole('heading', { level: 1, name: 'Konto' })).toBeInTheDocument();
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'empty');
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
