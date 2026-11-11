import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { App } from './App';

describe('passenger Startseite', () => {
  it('zeigt Ueberschrift und Design-System-Button', () => {
    render(<App />);
    expect(
      screen.getByRole('heading', { level: 1, name: 'TAKTOVIA Fahrgast' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fahrt suchen' })).toBeInTheDocument();
  });

  it('hat keine axe-Verstoesse', async () => {
    const { container } = render(<App />);
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
