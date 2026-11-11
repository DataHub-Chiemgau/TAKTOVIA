import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ResourceView } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

const empty = { title: 'Keine Gebiete', hint: 'Legen Sie ein Gebiet an.' };

describe('ResourceView (Lade-, Leer-, Fehler- und Rechtezustand eines Bildschirms)', () => {
  it('zeigt den Ladezustand', () => {
    render(
      <ResourceView state={{ status: 'loading' }} isEmpty={() => false} empty={empty}>
        {() => <p>Inhalt</p>}
      </ResourceView>,
    );
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'loading');
    expect(screen.queryByText('Inhalt')).toBeNull();
  });

  it('zeigt den Leerzustand mit Grund und nächster Handlung', async () => {
    const { container } = render(
      <ResourceView
        state={{ status: 'ready', data: [] as string[] }}
        isEmpty={(d) => d.length === 0}
        empty={empty}
      >
        {() => <p>Inhalt</p>}
      </ResourceView>,
    );
    expect(screen.getByRole('heading', { name: 'Keine Gebiete' })).toBeInTheDocument();
    expect(screen.getByText('Legen Sie ein Gebiet an.')).toBeInTheDocument();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt Daten, wenn vorhanden', () => {
    render(
      <ResourceView
        state={{ status: 'ready', data: ['a'] }}
        isEmpty={(d) => d.length === 0}
        empty={empty}
      >
        {(d) => <p>Erster: {d[0]}</p>}
      </ResourceView>,
    );
    expect(screen.getByText('Erster: a')).toBeInTheDocument();
  });

  it('403 zeigt den Rechtezustand ohne Daten, 500 den Fehlerzustand', () => {
    const { rerender } = render(
      <ResourceView
        state={{ status: 'error', error: { status: 403 } }}
        isEmpty={() => false}
        empty={empty}
      >
        {() => <p>Inhalt</p>}
      </ResourceView>,
    );
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden');
    rerender(
      <ResourceView
        state={{ status: 'error', error: { status: 500 } }}
        isEmpty={() => false}
        empty={empty}
      >
        {() => <p>Inhalt</p>}
      </ResourceView>,
    );
    expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'error');
    expect(screen.queryByText('Inhalt')).toBeNull();
  });
});
