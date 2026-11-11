import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ConflictDialog } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

describe('412-Konfliktdialog', () => {
  it('überschreibt nie still: ohne Klick wird keine Aktion ausgelöst, der Fokus liegt im Dialog', () => {
    const onReload = vi.fn();
    const onReview = vi.fn();
    render(
      <ConflictDialog open onReload={onReload} onReview={onReview} onCancel={() => undefined} />,
    );
    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toHaveAccessibleName(/geändert/i);
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(onReload).not.toHaveBeenCalled();
    expect(onReview).not.toHaveBeenCalled();
  });

  it('bietet Neu laden, Eingaben prüfen und Abbrechen, aber kein direktes Überschreiben', () => {
    const onReload = vi.fn();
    const onReview = vi.fn();
    render(
      <ConflictDialog open onReload={onReload} onReview={onReview} onCancel={() => undefined} />,
    );
    expect(screen.queryByRole('button', { name: /überschreiben/i })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Aktuellen Stand laden' }));
    expect(onReload).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Meine Eingaben prüfen' }));
    expect(onReview).toHaveBeenCalledTimes(1);
  });

  it('Escape bricht ab und Tab bleibt im Dialog (Fokusfalle)', () => {
    const onCancel = vi.fn();
    render(
      <ConflictDialog
        open
        onReload={() => undefined}
        onReview={() => undefined}
        onCancel={onCancel}
      />,
    );
    const buttons = screen.getAllByRole('button');
    const last = buttons[buttons.length - 1] as HTMLElement;
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(buttons[0]).toHaveFocus();
    fireEvent.keyDown(buttons[0] as HTMLElement, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
    fireEvent.keyDown(last, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('rendert nichts, wenn geschlossen, und besteht axe, wenn offen', async () => {
    const { container, rerender } = render(
      <ConflictDialog
        open={false}
        onReload={() => undefined}
        onReview={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
    rerender(
      <ConflictDialog
        open
        onReload={() => undefined}
        onReview={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
