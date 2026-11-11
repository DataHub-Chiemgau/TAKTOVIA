import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { App } from './App';

describe('operator Startseite', () => {
  it('zeigt Ueberschrift, Bereichsnavigation und Design-System-Button', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(
      screen.getByRole('heading', { level: 1, name: 'TAKTOVIA Operator' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Finanzen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leitstelle oeffnen' })).toBeInTheDocument();
  });

  it('Routen-Platzhalter Admin rendert', () => {
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Admin' })).toBeInTheDocument();
  });

  it('hat keine axe-Verstoesse', async () => {
    const { container } = render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
