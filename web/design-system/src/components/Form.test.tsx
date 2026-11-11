import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Field, Form } from '../index';
import { de } from '../i18n';
import { formatViolations, runAxe } from '../testing/axe';

const problem = {
  type: 'https://api.taktovia.example/problems/validation-failed',
  title: 'Validierung fehlgeschlagen',
  status: 422,
  code: 'VALIDATION_FAILED',
  correlation_id: '00000000-0000-4000-8000-000000000070',
  violations: [
    { pointer: '/legal_name', code: 'REQUIRED', message: 'FREITEXT-DARF-NICHT-ERSCHEINEN' },
    { pointer: '/unbekanntes_feld', code: 'INVALID_VALUE', message: 'FREITEXT-ZWEI' },
  ],
};

function Example() {
  return (
    <Form problem={problem} onSubmit={() => undefined} submitLabel="Speichern">
      <Field pointer="/legal_name" label="Name des Rechtsträgers" />
      <Field pointer="/address/street" label="Straße" />
    </Form>
  );
}

describe('Formular mit Feldfehlern aus violations[].pointer', () => {
  it('zeigt den Fehler am richtigen Feld mit dem Text aus dem Code-Katalog, nicht dem Freitext', () => {
    render(<Example />);
    const input = screen.getByLabelText('Name des Rechtsträgers');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(de['violation.REQUIRED']);
    expect(screen.getByLabelText('Straße')).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText(/FREITEXT/)).toBeNull();
  });

  it('eine Verletzung ohne passendes Feld erscheint in der Zusammenfassung, nicht verloren', () => {
    render(<Example />);
    const summary = screen.getByRole('alert');
    expect(summary).toHaveTextContent('/unbekanntes_feld');
    expect(summary).toHaveTextContent(de['violation.INVALID_VALUE']);
    expect(summary).not.toHaveTextContent('FREITEXT');
  });

  it('die Zusammenfassung verlinkt auf das fehlerhafte Feld; Klick setzt den Fokus', () => {
    render(<Example />);
    fireEvent.click(screen.getByRole('link', { name: /Name des Rechtsträgers/ }));
    expect(screen.getByLabelText('Name des Rechtsträgers')).toHaveFocus();
  });

  it('Absenden ruft onSubmit genau einmal und sperrt währenddessen', async () => {
    const onSubmit = vi.fn(() => new Promise<void>(() => undefined));
    render(
      <Form onSubmit={onSubmit} submitLabel="Speichern">
        <Field pointer="/a" label="A" />
      </Form>,
    );
    const submit = screen.getByRole('button', { name: 'Speichern' });
    fireEvent.click(submit);
    fireEvent.click(submit);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('besteht axe mit Fehlern', async () => {
    const { container } = render(<Example />);
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
