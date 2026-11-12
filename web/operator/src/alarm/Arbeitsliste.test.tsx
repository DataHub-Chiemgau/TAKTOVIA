import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { Alert, ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const ALERT: Alert = {
  id: '00000000-0000-4000-8000-0000000000a1',
  kind: 'VEHICLE_BLOCK_CRITICAL',
  priority: 'CRITICAL',
  state: 'OPEN',
  vehicle_id: '00000000-0000-4000-8000-0000000000b1',
  service_id: null,
  recipient_role: 'dispatcher',
  policy_revision_no: 1,
  escalation_level: 1,
  escalated_at: '2026-12-01T08:00:00Z',
  escalate_at: null,
  reason_code: null,
  ref_type: null,
  ref_id: null,
  created_at: '2026-12-01T08:00:00Z',
  updated_at: '2026-12-01T08:00:00Z',
  resolved_at: null,
  revision: 1,
  acknowledgement: null,
};

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'operator:read', 'operator:write']) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
    // der Live-Kanal ist in diesen Tests nicht verfügbar: die Ansicht kennzeichnet sich entsprechend
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/leitstelle/stoerungen']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: Alert[]) => ({ items, next_cursor: null, as_of: '2026-12-01T08:05:00Z' });

describe('UI-07 Arbeitsliste Offene Störungen', () => {
  it('zeigt Priorität, Alter, Eskalation und quittiert mit Ursache', async () => {
    let list = [ALERT];
    const { calls } = setup((id) => {
      if (id === 'listOperatorAlerts') return page(list);
      if (id === 'acknowledgeOperatorAlert') {
        list = [];
        return { ...ALERT, state: 'ACKNOWLEDGED', revision: 2 };
      }
      throw new ApiError(404);
    });
    expect(await screen.findByText('Kritische Sperre')).toBeTruthy();
    expect(screen.getByText('kritisch')).toBeTruthy();
    expect(screen.getByText(/Stufe 1 seit/)).toBeTruthy();
    expect(screen.getByText(/Min\./)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Ursache zu Kritische Sperre/), {
      target: { value: 'REPAIRED' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Quittieren' }));
    await waitFor(() => expect(screen.getByText('Keine offenen Störungen')).toBeTruthy());
    const ack = calls.find((c) => c.id === 'acknowledgeOperatorAlert');
    expect(ack?.args).toMatchObject({
      path: { alert_id: ALERT.id },
      body: { cause: 'REPAIRED' },
      revision: 1,
    });
    expect(screen.getByText('Der Alarm wurde quittiert.')).toBeTruthy();
  });

  it('zeigt den Leerzustand, kennzeichnet den fehlenden Live-Kanal als veraltet und zeigt den Datenstand', async () => {
    setup((id) => {
      if (id === 'listOperatorAlerts') return page([]);
      throw new ApiError(404);
    });
    expect(await screen.findByText('Keine offenen Störungen')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).not.toBe('live'));
    expect(screen.getByTestId('live-status').textContent).toMatch(
      /veraltet|unterbrochen|Verbindung/i,
    );
  });

  it('zeigt den Ladefehler und lädt erneut', async () => {
    let fail = true;
    setup((id) => {
      if (id === 'listOperatorAlerts') {
        if (fail) throw new ApiError(503);
        return page([ALERT]);
      }
      throw new ApiError(404);
    });
    const retry = await screen.findByRole('button', { name: 'Erneut versuchen' });
    fail = false;
    fireEvent.click(retry);
    expect(await screen.findByText('Kritische Sperre')).toBeTruthy();
  });

  it('bei gleichzeitiger Quittierung (412) wird neu geladen und der Grund genannt', async () => {
    let n = 0;
    setup((id) => {
      if (id === 'listOperatorAlerts') {
        n += 1;
        return page(n === 1 ? [ALERT] : [{ ...ALERT, state: 'ACKNOWLEDGED', revision: 2 }]);
      }
      if (id === 'acknowledgeOperatorAlert') throw new ApiError(412);
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Quittieren' }));
    expect(await screen.findByText(/gleichzeitig geändert/)).toBeTruthy();
  });

  it('ohne Quittierungsrecht gibt es keine Schaltfläche, nur die Anzeige', async () => {
    setup(
      (id) => {
        if (id === 'listOperatorAlerts') return page([ALERT]);
        throw new ApiError(404);
      },
      ['me:read', 'operator:read'],
    );
    expect(await screen.findByText('Kritische Sperre')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Quittieren' })).toBeNull();
    expect(screen.getByText('offen')).toBeTruthy();
  });

  it('ohne Leserecht erscheint der Bereich nicht', async () => {
    setup(() => {
      throw new ApiError(404);
    }, ['me:read']);
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByText('Keine offenen Störungen')).toBeNull();
  });

  it('hat keine Zugänglichkeitsverstöße', async () => {
    const { container } = setup((id) => {
      if (id === 'listOperatorAlerts') return page([ALERT]);
      throw new ApiError(404);
    });
    await screen.findByText('Kritische Sperre');
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });
});
