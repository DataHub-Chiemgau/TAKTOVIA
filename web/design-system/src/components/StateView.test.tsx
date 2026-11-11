import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EmptyState, ErrorState, StateView } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

const cases = [
  [401, 'unauthenticated', /Anmeldung/i],
  [403, 'forbidden', /Berechtigung/i],
  [404, 'notFound', /nicht gefunden/i],
  [412, 'conflict', /geändert/i],
  [428, 'preconditionRequired', /Stand/i],
  [429, 'rateLimited', /Zu viele/i],
] as const;

describe('API-Fehler erzeugen definierte Zustände', () => {
  it.each(cases)(
    'Status %i zeigt den Zustand %s mit verständlichem Text',
    async (status, kind, text) => {
      const { container } = render(<ErrorState error={{ status }} />);
      const view = screen.getByTestId('state-view');
      expect(view).toHaveAttribute('data-kind', kind);
      expect(view).toHaveTextContent(text);
      expect(formatViolations(await runAxe(container))).toBe('');
    },
  );

  it('429 nennt die Wartezeit aus Retry-After', () => {
    render(<ErrorState error={{ status: 429, retryAfterSeconds: 30 }} />);
    expect(screen.getByTestId('state-view')).toHaveTextContent('30');
  });

  it('403 gibt keine Einzelheiten preis, auch wenn der Server Text mitschickt', () => {
    render(
      <ErrorState
        error={{
          status: 403,
          problem: {
            type: 't',
            title: 'GEHEIMER-TEXT',
            status: 403,
            code: 'FORBIDDEN',
            correlation_id: '00000000-0000-4000-8000-000000000070',
            detail: 'GEHEIMER-DETAIL',
          },
        }}
      />,
    );
    expect(screen.queryByText(/GEHEIMER/)).toBeNull();
  });

  it('zeigt die Korrelations-ID als Hilfe für den Support, wenn vorhanden', () => {
    render(
      <ErrorState
        error={{
          status: 500,
          problem: {
            type: 't',
            title: 'x',
            status: 500,
            code: 'INTERNAL_ERROR',
            correlation_id: '00000000-0000-4000-8000-000000000070',
          },
        }}
      />,
    );
    expect(screen.getByText(/00000000-0000-4000-8000-000000000070/)).toBeInTheDocument();
  });
});

describe('Zustandskomponenten', () => {
  it('Leerzustand benennt den Grund und optional die nächste Handlung', () => {
    render(<EmptyState title="Keine Revisionen" hint="Legen Sie die erste Revision an." />);
    expect(screen.getByRole('heading', { name: 'Keine Revisionen' })).toBeInTheDocument();
    expect(screen.getByText('Legen Sie die erste Revision an.')).toBeInTheDocument();
  });

  it('Fehlerzustände sind eine Live-Region mit Rolle alert, Leerzustände nicht', () => {
    const { rerender } = render(<StateView kind="error" />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    rerender(<StateView kind="empty" />);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('Aktion wie „Erneut versuchen“ wird gerendert', () => {
    render(<StateView kind="unavailable" action={<button>Erneut versuchen</button>} />);
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });
});
