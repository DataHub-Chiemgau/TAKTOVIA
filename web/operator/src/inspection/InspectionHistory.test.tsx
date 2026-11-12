import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Inspection, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { App } from '../App';
import { answerText, historyRows } from './InspectionHistory';

const ID = '00000000-0000-4000-8000-0000000000a1';

/** Abgeschlossene Prüfung: zwei Fragen der damaligen Vorlage (Version 1), ein Mangel mit Beschreibung, ein Nachtrag und ein begründet freigegebener Kilometerstand. */
const INSPECTION: Inspection = {
  id: ID,
  shift_id: '00000000-0000-4000-8000-0000000000a2',
  vehicle_id: '00000000-0000-4000-8000-0000000000a3',
  driver_ref: '00000000-0000-4000-8000-0000000000a4',
  template_revision_id: '00000000-0000-4000-8000-0000000000a5',
  kind: 'PRE_SHIFT',
  status: 'COMPLETED',
  outcome: 'PASSED',
  missing_requirements: [],
  completed_by: 'user:11111111-1111-4111-8111-111111111111',
  answers: [
    {
      step_code: 'BELEUCHTUNG',
      answer: 'DEFECT',
      note: 'Blinker links defekt',
      answered_at: '2026-11-12T06:00:00Z',
    },
    {
      step_code: 'ODO',
      answer: 'OK',
      number_value: 9000,
      answered_at: '2026-11-12T06:01:00Z',
      plausibility: 'BEGRUENDET_FREIGEGEBEN',
      previous_value: 10000,
      justification: {
        reason: 'Tacho defekt',
        justified_by: 'user:22222222-2222-4222-8222-222222222222',
        justified_at: '2026-11-12T06:02:00Z',
      },
    },
  ],
  amendments: [
    {
      id: '00000000-0000-4000-8000-0000000000b1',
      inspection_id: ID,
      step_code: 'BELEUCHTUNG',
      answer_id: '00000000-0000-4000-8000-0000000000b2',
      version: 1,
      kind: 'CORRECTION',
      new_answer: 'OK',
      new_note: null,
      reason: 'Blinker getauscht',
      amended_by: 'user:33333333-3333-4333-8333-333333333333',
      created_at: '2026-11-12T08:00:00Z',
    },
  ],
  release: null,
  started_at: '2026-11-12T05:55:00Z',
  completed_at: '2026-11-12T06:05:00Z',
  received_at: '2026-11-12T06:05:02Z',
  revision: 2,
  template: {
    template_revision_id: '00000000-0000-4000-8000-0000000000a5',
    template_id: '00000000-0000-4000-8000-0000000000a6',
    version: 1,
    vehicle_class: 'PKW',
    service_mode: 'STANDARD',
    step_count: 2,
    steps: [
      {
        step_code: 'BELEUCHTUNG',
        position: 1,
        title: 'Beleuchtung',
        mandatory: true,
        safety_critical: false,
        answer_kind: 'OK_DEFECT_NA',
        photo_required_on_defect: false,
      },
      {
        step_code: 'ODO',
        position: 2,
        title: 'Kilometerstand',
        mandatory: true,
        safety_critical: false,
        answer_kind: 'ODOMETER_KM',
        photo_required_on_defect: false,
      },
    ],
  },
};

type Call = { id: OperationId; args: Record<string, unknown> };

function setup(scopes: string[], inspection: Inspection = INSPECTION) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['workshop'], scopes }));
      if (id === 'getInspection') return Promise.resolve(inspection);
      if (id === 'createInspectionAmendment') return Promise.resolve({});
      return Promise.reject(new Error('unerwartet ' + id));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/pruefungen']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-11 Prüfnachweis', () => {
  it('historie_zeigt_ursprungsfragen_und_antworten', async () => {
    const { container } = setup(['inspections:justify']);
    fireEvent.change(await screen.findByLabelText('Prüf-ID'), { target: { value: ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Prüfung anzeigen' }));
    const table = await screen.findByRole('table', { name: /Fragen und Originalantworten/ });
    const rows = within(table).getAllByRole('row');
    const row = (i: number): HTMLElement => {
      const r = rows[i];
      if (!r) throw new Error('Zeile fehlt: ' + i);
      return r;
    };
    // Fragen der damaligen Vorlage, Originalantworten unverändert, Nachtrag daneben (nicht anstelle)
    expect(within(row(1)).getByText('1. Beleuchtung')).toBeTruthy();
    expect(within(row(1)).getByText('Mangel · „Blinker links defekt“')).toBeTruthy();
    expect(within(row(1)).getByText(/v1 OK – Blinker getauscht \(user:3333/)).toBeTruthy();
    expect(
      within(row(2)).getByText('Kilometerstand'.replace('Kilometerstand', '2. Kilometerstand')),
    ).toBeTruthy();
    expect(within(row(2)).getByText('OK · 9000')).toBeTruthy();
    expect(
      within(row(2)).getByText(/begründet freigegeben: Tacho defekt \(user:2222/),
    ).toBeTruthy();
    expect(screen.getByText('user:11111111-1111-4111-8111-111111111111')).toBeTruthy();
    expect(screen.getByText('Version 1')).toBeTruthy();
    // ohne Nachtragsrecht keine Eingabe
    expect(screen.queryByRole('button', { name: 'Nachtrag speichern' })).toBeNull();
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('nachtrag_verlangt_recht_und_sendet_pflichtgrund', async () => {
    const { calls } = setup(['inspections:justify', 'inspections:amend']);
    fireEvent.change(await screen.findByLabelText('Prüf-ID'), { target: { value: ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Prüfung anzeigen' }));
    await screen.findByRole('button', { name: 'Nachtrag speichern' });
    fireEvent.change(screen.getByLabelText('Prüfelement (Schlüssel)'), {
      target: { value: 'ODO' },
    });
    fireEvent.change(screen.getByLabelText('Neue Antwort (OK, DEFECT, NOT_APPLICABLE)'), {
      target: { value: 'OK' },
    });
    fireEvent.change(screen.getByLabelText('Grund (Pflicht)'), { target: { value: 'Tippfehler' } });
    fireEvent.click(screen.getByRole('button', { name: 'Nachtrag speichern' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createInspectionAmendment')).toBe(true));
    const call = calls.find((c) => c.id === 'createInspectionAmendment');
    expect(call?.args).toMatchObject({
      path: { inspection_id: ID },
      body: { step_code: 'ODO', kind: 'CORRECTION', new_answer: 'OK', reason: 'Tippfehler' },
    });
    await screen.findByText(/Originalantwort bleibt unverändert/);
  });

  it('hilfsfunktionen_zeigen_zahl_text_und_mangel', () => {
    expect(answerText({ answer: 'OK', number_value: 55.5 })).toBe('OK · 55.5');
    expect(answerText({ answer: 'DEFECT', note: 'Kratzer' })).toBe('Mangel · „Kratzer“');
    expect(historyRows(INSPECTION).map((r) => r.code)).toEqual(['BELEUCHTUNG', 'ODO']);
  });
});
