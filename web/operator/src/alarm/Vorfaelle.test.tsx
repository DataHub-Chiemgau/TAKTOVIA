import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Incident, OperationId, WorkflowLogEntry } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { outcomeLabel } from './Vorfaelle';

const VEHICLE = '00000000-0000-4000-8000-0000000000b1';
const INCIDENT: Incident = {
  id: '00000000-0000-4000-8000-0000000000c1',
  kind: 'VEHICLE_OUTAGE',
  vehicle_id: VEHICLE,
  status: 'OPEN',
  dedupe_key: `VEHICLE_OUTAGE:${VEHICLE}`,
  opened_at: '2026-12-01T08:00:00Z',
  updated_at: '2026-12-01T08:03:00Z',
  rule_revision_id: '00000000-0000-4000-8000-0000000000d1',
  affected_run_ids: [],
  defect_ids: ['00000000-0000-4000-8000-0000000000e1'],
  replacement_operation_id: '00000000-0000-4000-8000-0000000000f1',
  service_id: null,
  closed_at: null,
  revision: 3,
};

const entry = (seq: number, e: Partial<WorkflowLogEntry>): WorkflowLogEntry => ({
  seq,
  at: `2026-12-01T08:0${seq}:00Z`,
  event_id: '00000000-0000-4000-8000-0000000000a1',
  event_type: 'VEHICLE_BLOCKED',
  rule_revision_id: '00000000-0000-4000-8000-0000000000d1',
  rule_revision_no: 1,
  action: 'EVALUATE_RULE',
  outcome: 'MATCHED:2',
  decision: 'RULE_MATCHED',
  actor: 'system:workflow',
  correlation_id: null,
  ref: null,
  reason_code: null,
  ...e,
});

const LOG: WorkflowLogEntry[] = [
  entry(1, {}),
  entry(2, { action: 'OPEN_INCIDENT', outcome: 'CREATED', decision: null }),
  entry(3, {
    action: 'START_REPLACEMENT_SEARCH',
    outcome: 'STARTED',
    decision: null,
    ref: INCIDENT.replacement_operation_id,
  }),
  entry(4, {
    action: 'SEARCH_RESULT',
    outcome: 'FAILED:PROVIDER_DOWN',
    event_type: 'REPLACEMENT_SEARCH',
    decision: 'SEARCH_FAILED',
  }),
  entry(5, {
    action: 'START_REPLACEMENT_SEARCH',
    outcome: 'ALREADY_STARTED',
    event_type: 'DISRUPTION_REPORTED',
    decision: null,
  }),
];

/** Text eines Listeneintrags (ohne nicht-null-Zusicherung). */
const text = (el: HTMLElement | undefined): string => el?.textContent ?? '';

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'operator:read']) {
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
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/leitstelle/vorfaelle']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: Incident[]) => ({ items, next_cursor: null, as_of: '2026-12-01T08:05:00Z' });

describe('UI-07 Vorfälle und Zeitleiste', () => {
  it('zeigt Fahrzeug, Ursache, Zustand und Verknüpfungen und die Zeitleiste mit allen Folgeaktionen', async () => {
    const { calls } = setup((id) => {
      if (id === 'listOperatorIncidents') return page([INCIDENT]);
      if (id === 'getOperatorIncident') return { ...INCIDENT, workflow_log: LOG };
      throw new ApiError(404);
    });
    expect(await screen.findByText('Fahrzeugausfall')).toBeTruthy();
    expect(screen.getByText('offen')).toBeTruthy();
    expect(screen.getByText(VEHICLE)).toBeTruthy();
    expect(screen.getByText('1 Schadenakten, 0 Fahrten')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Ablauf anzeigen' }));
    const timeline = await screen.findByRole('region', { name: 'Zeitleiste des Vorfalls' });
    expect(calls.find((c) => c.id === 'getOperatorIncident')?.args).toMatchObject({
      path: { incident_id: INCIDENT.id },
    });
    const items = within(timeline).getAllByRole('listitem');
    expect(items).toHaveLength(5);
    // Reihenfolge der Ordnungsnummer, Auslöser, Regelversion, Entscheidung, Akteur
    expect(text(items[0])).toMatch(/1\. Regel angewendet/);
    expect(text(items[0])).toMatch(/Auslöser: Fahrzeug gesperrt/);
    expect(text(items[0])).toMatch(/Regelversion 1/);
    expect(text(items[0])).toMatch(/Entscheidung: RULE_MATCHED/);
    expect(text(items[0])).toMatch(/Akteur: system:workflow/);
    expect(text(items[2])).toMatch(/Ersatzfahrzeugsuche starten/);
    expect(text(items[2])).toMatch(/gestartet/);
    // fehlgeschlagene und übersprungene Aktionen sind sichtbar, mit Text und nicht nur mit Farbe
    expect(text(items[3])).toMatch(/fehlgeschlagen \(PROVIDER_DOWN\)/);
    expect(text(items[4])).toMatch(/übersprungen \(ALREADY_STARTED\)/);
  });

  it('zeigt Leerzustand, Datenstand und kennzeichnet den fehlenden Live-Kanal', async () => {
    setup((id) => {
      if (id === 'listOperatorIncidents') return page([]);
      throw new ApiError(404);
    });
    expect(await screen.findByText('Keine Vorfälle')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('live-status').dataset.state).not.toBe('live'));
  });

  it('zeigt den Ladefehler und lädt erneut', async () => {
    let fail = true;
    setup((id) => {
      if (id === 'listOperatorIncidents') {
        if (fail) throw new ApiError(503);
        return page([INCIDENT]);
      }
      throw new ApiError(404);
    });
    const retry = await screen.findByRole('button', { name: 'Erneut versuchen' });
    fail = false;
    fireEvent.click(retry);
    expect(await screen.findByText('Fahrzeugausfall')).toBeTruthy();
  });

  it('zeigt für ein Protokoll ohne Einträge den Leerzustand der Zeitleiste', async () => {
    setup((id) => {
      if (id === 'listOperatorIncidents') return page([INCIDENT]);
      if (id === 'getOperatorIncident') return { ...INCIDENT, workflow_log: [] };
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Ablauf anzeigen' }));
    expect(await screen.findByText('Kein Ablauf protokolliert')).toBeTruthy();
  });

  it('zeigt bei fehlendem Recht des Servers (403) den Fehlerzustand der Zeitleiste', async () => {
    setup((id) => {
      if (id === 'listOperatorIncidents') return page([INCIDENT]);
      if (id === 'getOperatorIncident') throw new ApiError(403);
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Ablauf anzeigen' }));
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Zeitleiste des Vorfalls' })).not.toBeNull(),
    );
    expect(screen.queryByText('Kein Ablauf protokolliert')).toBeNull();
  });

  it('ohne Leserecht erscheint der Bereich nicht', async () => {
    setup(() => {
      throw new ApiError(404);
    }, ['me:read']);
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByText('Keine Vorfälle')).toBeNull();
  });

  it('übersetzt Ergebnisse als Text mit Ton', () => {
    expect(outcomeLabel({ outcome: 'FAILED:X' })).toEqual({
      text: 'fehlgeschlagen (X)',
      tone: 'error',
    });
    expect(outcomeLabel({ outcome: 'SKIPPED_NO_SUBJECT' }).tone).toBe('warning');
    expect(outcomeLabel({ outcome: 'PROPOSALS:3' }).text).toBe('3 Vorschläge');
    expect(outcomeLabel({ outcome: 'NO_RULE' }).text).toBe('keine Aktion');
  });

  it('hat keine Zugänglichkeitsverstöße', async () => {
    const { container } = setup((id) => {
      if (id === 'listOperatorIncidents') return page([INCIDENT]);
      if (id === 'getOperatorIncident') return { ...INCIDENT, workflow_log: LOG };
      throw new ApiError(404);
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Ablauf anzeigen' }));
    await screen.findByRole('region', { name: 'Zeitleiste des Vorfalls' });
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });
});
