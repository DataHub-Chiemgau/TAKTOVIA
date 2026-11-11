import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button, PageShell } from './index';
import { formatViolations, runAxe } from './testing/axe';

describe('design-system', () => {
  it('rendert den Button mit zugaenglichem Namen', () => {
    render(<Button>Speichern</Button>);
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeInTheDocument();
  });

  it('PageShell ist ohne axe-Verstoesse', async () => {
    const { container } = render(<PageShell title="Start" />);
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
  });

  it('axe erkennt eine Schaltflaeche ohne zugaenglichen Namen (negativ)', async () => {
    const { container } = render(<Button />);
    const violations = await runAxe(container);
    expect(violations.map((v) => v.id)).toContain('button-name');
  });
});
