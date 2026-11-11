import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BusyButton, LiveRegionProvider, StatusBadge, useAnnounce } from '../index';
import { formatViolations, runAxe } from '../testing/axe';

describe('StatusBadge', () => {
  it('nennt den Status im Text und trägt ein Symbol, nie nur Farbe', async () => {
    const { container } = render(
      <>
        <StatusBadge tone="ok">Freigegeben</StatusBadge>
        <StatusBadge tone="warning">Veraltet</StatusBadge>
        <StatusBadge tone="error">Gesperrt</StatusBadge>
        <StatusBadge tone="info">Entwurf</StatusBadge>
      </>,
    );
    for (const text of ['Freigegeben', 'Veraltet', 'Gesperrt', 'Entwurf'])
      expect(screen.getByText(text)).toBeInTheDocument();
    const icons = [...container.querySelectorAll('[data-icon]')].map((n) => n.textContent);
    expect(new Set(icons).size).toBe(4);
    expect(container.querySelectorAll('[data-icon][aria-hidden="true"]').length).toBe(4);
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});

describe('Live-Region', () => {
  function Announcer() {
    const announce = useAnnounce();
    return <button onClick={() => announce('Gespeichert')}>Melden</button>;
  }

  it('ist beim Start vorhanden, höflich und liest eine asynchrone Meldung vor', async () => {
    render(
      <LiveRegionProvider>
        <Announcer />
      </LiveRegionProvider>,
    );
    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveTextContent('');
    fireEvent.click(screen.getByRole('button', { name: 'Melden' }));
    await waitFor(() => expect(region).toHaveTextContent('Gespeichert'));
  });
});

describe('BusyButton (Mehrfachabsendeschutz)', () => {
  it('sperrt bei laufender Operation, ein Doppelklick löst genau einmal aus', async () => {
    let finish: () => void = () => undefined;
    const action = vi.fn(() => new Promise<void>((r) => (finish = r)));
    render(<BusyButton onAction={action}>Buchen</BusyButton>);
    const button = screen.getByRole('button', { name: 'Buchen' });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(action).toHaveBeenCalledTimes(1);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAttribute('aria-busy', 'true');
    await act(async () => finish());
    expect(button).not.toHaveAttribute('aria-busy', 'true');
    fireEvent.click(button);
    expect(action).toHaveBeenCalledTimes(2);
  });

  it('entsperrt nach einem Fehler und meldet ihn über onError', async () => {
    const onError = vi.fn();
    const action = vi.fn(() => Promise.reject(new Error('x')));
    render(
      <BusyButton onAction={action} onError={onError}>
        Senden
      </BusyButton>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Senden' }));
    await waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: 'Senden' })).not.toHaveAttribute('aria-busy', 'true');
  });

  it('bleibt fokussierbar, wenn gesperrt (aria-disabled statt disabled), und besteht axe', async () => {
    const { container } = render(
      <BusyButton onAction={() => new Promise<void>(() => undefined)}>Buchen</BusyButton>,
    );
    const button = screen.getByRole('button', { name: 'Buchen' });
    button.focus();
    fireEvent.click(button);
    expect(button).toHaveFocus();
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
