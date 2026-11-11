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

async function navLinks() {
  const nav = await screen.findByRole('navigation', { name: 'Hauptnavigation' });
  return within(nav)
    .getAllByRole('link')
    .map((a) => a.textContent);
}

describe('operator Anwendungsrahmen', () => {
  it('/v1/me mit Status 403 (keine Mitgliedschaft) zeigt einen verständlichen Zustand ohne Rahmen, Navigation und Daten', async () => {
    setup(new ApiError(403));
    const state = await screen.findByTestId('auth-state');
    expect(state).toHaveAttribute('data-kind', 'noMembership');
    expect(state).toHaveTextContent('Keine Mitgliedschaft');
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByTestId('state-view')).toBeNull();
  });

  it('zeigt den Ladezustand, danach den Rahmen mit Mandant aus /v1/me', async () => {
    setup(['ADMIN']);
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'loading');
    expect(await screen.findByRole('heading', { level: 1, name: 'Start' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Arbeitskontext' })).toHaveTextContent(
      'Verkehrsbetrieb Nord',
    );
  });

  it('Admin-Rahmen zeigt nur Navigationspunkte, für die /v1/me Rechte liefert (AK 3)', async () => {
    setup(['ADMIN']);
    const links = await navLinks();
    expect(links).toEqual([
      'Start',
      'Gebiete, Stops, Tarife',
      'Kooperation und Rollen',
      'Datenschutz, Audit, Support',
    ]);
    expect(links).not.toContain('Finanzen');
    expect(links).not.toContain('Personal');
  });

  it('rollenbezogene Navigation: Finanzrolle sieht Finanzen, aber keinen Adminbereich', async () => {
    setup(['FINANCE']);
    expect(await navLinks()).toEqual(['Start', 'Finanzen']);
  });

  it('ohne bekannte Rolle bleibt nur der Start sichtbar', async () => {
    setup(['UNBEKANNT']);
    expect(await navLinks()).toEqual(['Start']);
  });

  it('Direktaufruf eines Bereichs ohne Recht zeigt den Rechtezustand und keine Daten', async () => {
    setup(['FINANCE'], '/admin/datenschutz');
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('heading', { name: 'Datenschutz, Audit, Support' })).toBeNull();
  });

  // UI-17 ist seit WP-M1-20 kein Grundgerüst mehr: Revisionsliste und Parameterkatalog, siehe config/ConfigRevisions.test.tsx.

  it('UI-20 zeigt den lesenden Inventar-Export mit Mandanten-, Dienst- und Datenstand-Kontext', async () => {
    const { container } = setup(['ADMIN'], '/admin/datenschutz');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Datenschutz, Audit, Support' }),
    ).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: /passenger_private/ })).toBeInTheDocument();
    const ctx = screen.getByRole('region', { name: 'Arbeitskontext' });
    expect(ctx).toHaveTextContent('Verkehrsbetrieb Nord');
    expect(ctx).toHaveTextContent('Datenstand');
    expect(formatViolations(await runAxe(container))).toBe('');
  }, 30_000);

  it('Dienstumfang der Sitzung erscheint im Kontext', async () => {
    const client = fakeApiClient({
      getMe: () =>
        sampleMe({
          roles: ['ADMIN'],
          service_scope: [
            '00000000-0000-4000-8000-0000000000aa',
            '00000000-0000-4000-8000-0000000000ab',
          ],
        }),
    });
    render(
      <MemoryRouter initialEntries={['/admin/gebiete']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Arbeitskontext' })).toHaveTextContent('2 Dienste'),
    );
  });

  it.each([
    [401, 'unauthenticated'],
    [404, 'notFound'],
    [429, 'rateLimited'],
    [503, 'unavailable'],
  ])(
    '/v1/me mit Status %i zeigt den Zustand %s ohne Rahmen und ohne Navigation',
    async (status, kind) => {
      setup(new ApiError(status));
      await waitFor(() =>
        expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', kind),
      );
      expect(screen.queryByRole('navigation')).toBeNull();
    },
  );

  it('unbekannte Adresse zeigt die Fehlerseite „Nicht gefunden“', async () => {
    setup(['ADMIN'], '/gibt-es-nicht');
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'notFound'),
    );
  });

  it('Startseite und Bereiche haben keine axe-Verstöße', async () => {
    const { container } = setup(['ADMIN', 'FINANCE', 'STAFF_PLANNING', 'CALLCENTER']);
    await screen.findByRole('heading', { level: 1, name: 'Start' });
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
