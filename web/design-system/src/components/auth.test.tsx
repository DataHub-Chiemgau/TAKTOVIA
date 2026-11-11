import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthStateView } from './AuthStateView';
import { SessionList } from './SessionList';
import { TenantSwitcher } from './TenantSwitcher';
import { formatViolations, runAxe } from '../testing/axe';

describe('Zustände vor und außerhalb einer gültigen Sitzung', () => {
  it.each([
    ['signedOut', 'Anmeldung erforderlich', true],
    ['expired', 'Sitzung abgelaufen', true],
    ['revoked', 'Sitzung beendet oder gesperrt', true],
    ['error', 'Anmeldung fehlgeschlagen', true],
    ['mfaRequired', 'Zweiter Faktor erforderlich', true],
    ['noMembership', 'Keine Mitgliedschaft', false],
    ['signingIn', 'Anmeldung läuft', false],
  ] as const)(
    '%s zeigt Titel, keinen Datenbereich und nur sinnvolle Aktionen',
    async (kind, title, hasSignIn) => {
      const onSignIn = vi.fn();
      const { container } = render(<AuthStateView kind={kind} onSignIn={onSignIn} />);
      expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument();
      const button = screen.queryByRole('button');
      expect(button !== null).toBe(hasSignIn);
      if (button) {
        fireEvent.click(button);
        expect(onSignIn).toHaveBeenCalledTimes(1);
      }
      expect(formatViolations(await runAxe(container))).toBe('');
    },
  );

  it('der Zustand „gesperrt“ nennt keine Einzelheiten des Servers', () => {
    render(<AuthStateView kind="revoked" />);
    expect(screen.getByTestId('auth-state').textContent).not.toMatch(
      /\d{3}|UNAUTHENTICATED|token/i,
    );
  });
});

describe('Mandantenwähler', () => {
  const tenants = [
    { id: 'a', name: 'Betrieb A' },
    { id: 'b', name: 'Betrieb B' },
  ];

  it('mit einem Mandanten nur Anzeige ohne Auswahl', () => {
    render(
      <TenantSwitcher
        tenants={[tenants[0] ?? { id: 'a', name: 'Betrieb A' }]}
        activeId="a"
        onSwitch={() => true}
      />,
    );
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.getByTestId('tenant-name')).toHaveTextContent('Betrieb A');
  });

  it('bewusster Wechsel: Auswahl allein wechselt nicht; ohne ungespeicherte Eingaben direkt', () => {
    const onSwitch = vi.fn(() => true);
    render(<TenantSwitcher tenants={tenants} activeId="a" onSwitch={onSwitch} />);
    fireEvent.change(screen.getByLabelText('Mandant wechseln'), { target: { value: 'b' } });
    expect(onSwitch).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    expect(onSwitch).toHaveBeenCalledWith('b');
  });

  it('mit ungespeicherten Eingaben fragt ein Dialog; „Hier bleiben“ wechselt nicht, Bestätigung wechselt', async () => {
    const onSwitch = vi.fn(() => true);
    const { container } = render(
      <TenantSwitcher
        tenants={tenants}
        activeId="a"
        onSwitch={onSwitch}
        dirtyForms={['Mitgliedschaft erteilen']}
      />,
    );
    fireEvent.change(screen.getByLabelText('Mandant wechseln'), { target: { value: 'b' } });
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toHaveTextContent('Mitgliedschaft erteilen');
    expect(formatViolations(await runAxe(container))).toBe('');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Hier bleiben' }));
    expect(onSwitch).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln' }));
    fireEvent.click(screen.getByRole('button', { name: 'Wechseln und Eingaben aufbewahren' }));
    expect(onSwitch).toHaveBeenCalledWith('b');
  });
});

describe('Sitzungsliste (UI-05 Basis)', () => {
  const rows = [
    {
      id: 's1',
      created_at: '2026-11-02T07:00:00Z',
      last_seen_at: '2026-11-02T08:00:00Z',
      revoked_at: null,
    },
    {
      id: 's2',
      created_at: '2026-11-01T07:00:00Z',
      last_seen_at: '2026-11-01T08:00:00Z',
      revoked_at: '2026-11-01T09:00:00Z',
    },
  ];

  it('zeigt dieses Gerät, sperrt nur aktive Sitzungen ab und bietet „Alle Geräte abmelden“', async () => {
    const onRevoke = vi.fn();
    const onRevokeAll = vi.fn();
    const { container } = render(
      <SessionList
        sessions={rows}
        currentId="s1"
        formatTime={(i) => i.slice(0, 10)}
        onRevoke={onRevoke}
        onRevokeAll={onRevokeAll}
      />,
    );
    expect(screen.getByRole('table')).toHaveTextContent('dieses Gerät');
    expect(screen.getAllByRole('button', { name: /^Abmelden/ })).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: /^Abmelden/ }));
    expect(onRevoke).toHaveBeenCalledWith(rows[0]);
    fireEvent.click(screen.getByRole('button', { name: 'Alle Geräte abmelden' }));
    expect(onRevokeAll).toHaveBeenCalledTimes(1);
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
