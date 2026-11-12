import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  CostObject,
  Expense,
  ExpenseSummary,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { Aufteilung } from './Aufteilung';
import { Belegpruefung } from './Belegpruefung';

const DOC = '00000000-0000-4000-8000-0000000000e1';
const LE = '00000000-0000-4000-8000-0000000000a1';
const CENTERS: CostObject[] = ['a', 'b', 'c'].map((c, i) => ({
  id: `00000000-0000-4000-8000-00000000c0${i + 1}`,
  kind: 'COST_CENTER' as const,
  reference: `KST-${c.toUpperCase()}`,
  name: `Kostenstelle ${c.toUpperCase()}`,
  legal_entity_id: LE,
  vehicle_id: null,
  active: true,
}));

const step = (s: 'CAPTURE' | 'REVIEW' | 'BUDGET', by: string, n: number) => ({
  id: `00000000-0000-4000-8000-00000000010${n}`,
  step: s,
  document_revision: 5,
  decision: 'APPROVED' as const,
  decided_by: by,
  reason: 'Begründung',
  decided_at: '2026-06-15T08:00:00Z',
});

const BASE: Expense = {
  document_id: DOC,
  legal_entity_id: LE,
  state: 'REVIEWED',
  source_channel: 'DRIVER_APP',
  document_kind: 'RECEIPT',
  case_ref: null,
  mime: 'image/jpeg',
  size_bytes: 1000,
  sha256: 'a'.repeat(43),
  received_at: '2026-06-15T07:00:00Z',
  scanned_at: '2026-06-15T07:01:00Z',
  sender_verified: null,
  integrity: 'VERIFIED',
  approvable: true,
  blockers: [],
  unconfirmed_fields: [],
  sum_mismatch: false,
  cost_objects: [CENTERS[0] as CostObject],
  fields: {
    amount_minor: 10001,
    currency: 'EUR',
    document_date: '2026-06-15',
    merchant: 'Tankstelle Nord',
    document_number: 'R-1',
  },
  approval_steps: [step('CAPTURE', 'fahrer-1', 1), step('REVIEW', 'pruefer-1', 2)],
  export_state: 'NOT_EXPORTED',
  revision: 5,
};
const SUMMARY: ExpenseSummary = {
  document_id: DOC,
  legal_entity_id: LE,
  state: 'REVIEWED',
  document_kind: 'RECEIPT',
  source_channel: 'DRIVER_APP',
  received_at: '2026-06-15T07:00:00Z',
  revision: 5,
  merchant: 'Tankstelle Nord',
  amount_minor: 10001,
  currency: 'EUR',
  document_date: '2026-06-15',
  export_state: 'NOT_EXPORTED',
  cost_event_id: null,
};

type Handlers = Partial<Record<OperationId, (args: Record<string, unknown>) => unknown>>;
type Call = { id: OperationId; args: Record<string, unknown> };
const problem = (status: number, code: string) =>
  new ApiError(status, {
    type: 'about:blank',
    title: code,
    status,
    code,
    correlation_id: '00000000-0000-4000-8000-0000000000ff',
  });

/** Modell des Servers für die Maske (kein Ersatz für die echten Backend-Tests; ein Browser-E2E ist hier NICHT ausgeführt). */
function setup(
  ui: React.ReactElement,
  subject: string,
  scopes: string[],
  handlers: Handlers,
  withBlob = true,
) {
  const calls: Call[] = [];
  const base = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ subject, roles: ['finance'], scopes }));
      const h = handlers[id];
      if (!h) return Promise.reject(new ApiError(404));
      const r = h(args);
      return r instanceof ApiError ? Promise.reject(r) : Promise.resolve(r);
    }),
  };
  const client = (withBlob
    ? {
        ...base,
        callBlob: vi.fn(() =>
          Promise.resolve({ blob: new Blob(['x'], { type: 'image/jpeg' }), mime: 'image/jpeg' }),
        ),
      }
    : base) as unknown as ApiClient;
  const view = render(
    <MemoryRouter>
      <SessionProvider client={client}>{ui}</SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const FIN = [
  'receipts:read',
  'receipts:write',
  'expenses:review',
  'expenses:approve',
  'costs:reverse',
];
const listOf = (items: ExpenseSummary[]) => () => ({
  items,
  next_cursor: null,
  as_of: '2026-06-15T09:00:00Z',
});

describe('UI-14 Belegprüfung und Freigabe', () => {
  it('e2e_064_arbeitsliste_original_pruefen_freigeben_einmal_gebucht', async () => {
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:original'), revokeObjectURL: vi.fn() });
    const { calls, container } = setup(<Belegpruefung />, 'freigeber-1', FIN, {
      listExpenses: listOf([SUMMARY]),
      getExpense: () => BASE,
      getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
      listExpenseRevisions: () => ({ items: [] }),
      approveExpense: () => ({
        expense_id: DOC,
        revision: 5,
        state: 'POSTED',
        posting_id: '00000000-0000-4000-8000-0000000000f1',
      }),
    });
    // Arbeitsliste mit Datenstand
    expect(await screen.findByRole('table', { name: 'Belege' })).toBeInTheDocument();
    expect(screen.getByText(/Datenstand der Liste/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    // links das unveränderte Original, rechts die Felder und die Freigabe
    expect(await screen.findByAltText('Originalbeleg (unverändert)')).toHaveAttribute(
      'src',
      'blob:original',
    );
    expect(await screen.findByText('Tankstelle Nord', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('100,01 EUR', { selector: 'dd' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Begründung'), {
      target: { value: 'Sachlich richtig' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Freigeben' }));
    expect(await screen.findByText('Freigegeben und gebucht.')).toBeInTheDocument();
    expect(calls.filter((c) => c.id === 'approveExpense')).toHaveLength(1); // die Kosten werden einmal gebucht
    expect(calls.find((c) => c.id === 'approveExpense')?.args).toMatchObject({
      revision: 5,
      body: { validated_document_revision: 5 },
    });
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('e2e_066_ocr_89_statt_39_korrekturhistorie_und_vorschlag_bleiben_sichtbar', async () => {
    setup(<Belegpruefung />, 'pruefer-9', FIN, {
      listExpenses: listOf([SUMMARY]),
      getExpense: () => ({ ...BASE, fields: { ...BASE.fields, amount_minor: 3900 } }),
      getReceiptExtraction: () => ({
        run: {},
        proposals: [],
        current: [
          {
            id: '00000000-0000-4000-8000-000000000a01',
            field: 'AMOUNT',
            occurrence: 0,
            origin: 'HUMAN',
            status: 'CORRECTED',
            raw_value: null,
            normalized_value: '3900',
            display_value: '39,00',
            qualifier: null,
            confidence: null,
            source: null,
            suspicious: false,
            corrects_field_id: '00000000-0000-4000-8000-000000000a00',
            decided_by: 'u',
            decided_at: '2026-06-15T08:00:00Z',
            flag_reason: null,
          },
          {
            id: '00000000-0000-4000-8000-000000000a00',
            field: 'AMOUNT',
            occurrence: 0,
            origin: 'OCR',
            status: 'PROPOSED',
            raw_value: '89,00',
            normalized_value: '8900',
            display_value: '89,00',
            qualifier: null,
            confidence: 0.62,
            source: null,
            suspicious: false,
            corrects_field_id: null,
            decided_by: null,
            decided_at: null,
            flag_reason: 'LOW_CONFIDENCE',
          },
        ],
      }),
      listExpenseRevisions: () => ({
        items: [
          {
            revision: 2,
            fields: { amount_minor: { old: 8900, new: 3900, confirmed: false } },
            edited_by: 'pruefer-9',
            edited_at: '2026-06-15T08:00:00Z',
            reason: 'Lesefehler 89,00 statt 39,00',
            edited_view_key: null,
          },
        ],
      }),
    });
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    const table = await screen.findByRole('table', { name: 'OCR-Vorschläge' });
    expect(within(table).getByText('62 %')).toBeInTheDocument(); // Konfidenz
    expect(within(table).getByText(/niedrige Konfidenz/)).toBeInTheDocument();
    expect(within(table).getByText('89,00 EUR')).toBeInTheDocument(); // der OCR-Vorschlag bleibt nachvollziehbar
    const history = await screen.findByRole('list', { name: 'Korrekturhistorie' });
    expect(history).toHaveTextContent('Brutto: 89,00 EUR → 39,00 EUR');
    expect(history).toHaveTextContent('Lesefehler 89,00 statt 39,00');
  });

  it('e2e_068_vier_augen_zeigt_die_zweite_person_und_blockaden_sperren_die_freigabe_mit_grund', async () => {
    setup(<Belegpruefung />, 'freigeber-2', FIN, {
      listExpenses: listOf([{ ...SUMMARY, state: 'WAITING_SECOND_APPROVAL' }]),
      getExpense: () => ({
        ...BASE,
        state: 'WAITING_SECOND_APPROVAL',
        approval_steps: [
          step('CAPTURE', 'fahrer-1', 1),
          step('REVIEW', 'pruefer-1', 2),
          step('BUDGET', 'freigeber-1', 3),
        ],
      }),
      getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
      listExpenseRevisions: () => ({ items: [] }),
    });
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    expect(
      await screen.findByText(/nicht freigeber-1 und nicht fahrer-1 \(Erfasser\)/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zweite Freigabe erteilen' })).toBeInTheDocument();
  });

  it('freigeben_deaktiviert_mit_grund_bei_pflichtfeldern_kostenzuordnung_und_split', async () => {
    setup(<Belegpruefung />, 'freigeber-1', FIN, {
      listExpenses: listOf([SUMMARY]),
      getExpense: () => ({
        ...BASE,
        approvable: false,
        blockers: ['UNCONFIRMED_FIELDS', 'COST_ASSIGNMENT_MISSING', 'SPLIT_INCOMPLETE'],
        cost_objects: [],
      }),
      getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
      listExpenseRevisions: () => ({ items: [] }),
    });
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    const reasons = await screen.findByRole('list', { name: 'Gründe der Sperre' });
    expect(reasons).toHaveTextContent('Pflichtfelder sind nicht bestätigt oder korrigiert.');
    expect(reasons).toHaveTextContent('Es fehlt die Kostenzuordnung');
    expect(reasons).toHaveTextContent('Die Aufteilung auf die Kostenobjekte ist unvollständig');
    fireEvent.change(screen.getByLabelText('Begründung'), { target: { value: 'Freigegeben' } });
    const approve = screen.getByRole('button', { name: 'Freigeben' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('aria-describedby', 'freigabe-gruende');
  });

  it('konfliktdialog_bei_412_ohne_stilles_ueberschreiben', async () => {
    const { calls } = setup(<Belegpruefung />, 'freigeber-1', FIN, {
      listExpenses: listOf([SUMMARY]),
      getExpense: (() => {
        let n = 0;
        return () => ({ ...BASE, revision: n++ === 0 ? 5 : 6 });
      })(),
      getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
      listExpenseRevisions: () => ({ items: [] }),
      approveExpense: () => problem(412, 'REVISION_MISMATCH'),
    });
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    fireEvent.change(await screen.findByLabelText('Begründung'), {
      target: { value: 'Freigegeben' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Freigeben' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).queryByRole('button', { name: /überschreiben/i })).toBeNull(); // es gibt absichtlich kein „Überschreiben“
    fireEvent.click(within(dialog).getByRole('button', { name: /Aktuellen Stand laden/i }));
    await waitFor(() => expect(calls.filter((c) => c.id === 'getExpense')).toHaveLength(2));
    expect(calls.filter((c) => c.id === 'approveExpense')).toHaveLength(1);
  });

  it('zustaende_leere_liste_ladefehler_fehlendes_recht_und_fremder_beleg', async () => {
    // leere Arbeitsliste: eigener Zustand mit Handlungshinweis
    const empty = setup(<Belegpruefung />, 'freigeber-1', FIN, { listExpenses: listOf([]) });
    expect(await screen.findByText('Keine Belege in diesem Status')).toBeInTheDocument();
    empty.unmount();
    // Ladefehler: Fehlerzustand mit „Erneut versuchen“, keine Daten
    const failing = vi.fn();
    const err = setup(<Belegpruefung />, 'freigeber-1', FIN, {
      listExpenses: () => {
        failing();
        return new ApiError(0);
      },
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Erneut versuchen' }));
    await waitFor(() => expect(failing).toHaveBeenCalledTimes(2));
    err.unmount();
    // fehlendes Recht (Server 403): Rechtezustand, keine Belegdaten
    const denied = setup(<Belegpruefung />, 'ohne-recht', ['receipts:read'], {
      listExpenses: () => problem(403, 'FORBIDDEN'),
    });
    expect(
      await screen.findByRole('heading', { name: /Keine Berechtigung|nicht berechtigt/i }),
    ).toBeInTheDocument();
    denied.unmount();
    // fremder Beleg: 404 ohne Datenpreisgabe
    setup(<Belegpruefung />, 'freigeber-1', FIN, {
      listExpenses: listOf([SUMMARY]),
      getExpense: () => new ApiError(404),
    });
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    expect(
      await screen.findByText(/nicht gefunden oder ist für Sie nicht sichtbar \(404\)/),
    ).toBeInTheDocument();
  });

  it('ohne_freigaberecht_sieht_der_benutzer_keine_aktive_freigabe_und_das_original_hat_einen_eigenen_zustand', async () => {
    setup(
      <Belegpruefung />,
      'pruefer-1',
      ['receipts:read', 'receipts:write', 'expenses:review'],
      {
        listExpenses: listOf([SUMMARY]),
        getExpense: () => BASE,
        getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
        listExpenseRevisions: () => ({ items: [] }),
      },
      false,
    );
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    fireEvent.change(await screen.findByLabelText('Begründung'), {
      target: { value: 'Freigegeben' },
    });
    expect(screen.getByRole('button', { name: 'Freigeben' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ablehnen' })).toBeDisabled();
    expect(
      screen.getByText('Das Original ist in dieser Umgebung nicht ladbar.'),
    ).toBeInTheDocument();
  });

  it('gegenbeleg_erstellen_bei_gebuchtem_beleg_nur_mit_recht', async () => {
    const posted = {
      ...BASE,
      state: 'POSTED',
      cost_event_id: '00000000-0000-4000-8000-0000000000f1',
    };
    const handlers: Handlers = {
      listExpenses: listOf([{ ...SUMMARY, state: 'POSTED', cost_event_id: posted.cost_event_id }]),
      getExpense: () => posted,
      getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
      listExpenseRevisions: () => ({ items: [] }),
      createCostEventReversal: () => ({
        event: { gross_minor: -2000 },
        journal: { accounting_date: '2026-10-05' },
        allocation: null,
      }),
    };
    const { calls } = setup(<Belegpruefung />, 'freigeber-1', FIN, handlers);
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    fireEvent.change(
      await screen.findByLabelText('Begründung', { selector: '#gegenbeleg-grund' }),
      { target: { value: 'Gutschrift Lieferant' } },
    );
    fireEvent.change(screen.getByLabelText(/Bruttobetrag der Korrektur/), {
      target: { value: '20,00' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Gegenbeleg erstellen' }));
    expect(
      await screen.findByText(/Gegenbeleg über -20,00 EUR gebucht am 2026-10-05/),
    ).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'createCostEventReversal')?.args).toMatchObject({
      body: { reason: 'Gutschrift Lieferant', credit_gross_minor: 2000 },
    });
  });

  it('route_finanzen_belege_ist_in_der_navigation_und_nur_mit_finanzrecht_erreichbar', async () => {
    const client = {
      call: vi.fn((id: OperationId) =>
        id === 'getMe'
          ? Promise.resolve(
              sampleMe({ roles: ['finance'], scopes: ['expenses:approve', 'receipts:read'] }),
            )
          : id === 'listExpenses'
            ? Promise.resolve({ items: [], next_cursor: null, as_of: '2026-06-15T09:00:00Z' })
            : Promise.reject(new ApiError(404)),
      ),
    } as unknown as ApiClient;
    render(
      <MemoryRouter initialEntries={['/finanzen/belege']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole('heading', { name: 'Belege prüfen und freigeben' }),
    ).toBeInTheDocument();
    expect(
      screen.getAllByRole('link', { name: 'Belege prüfen und freigeben' }).length,
    ).toBeGreaterThan(0);
  });
});

describe('UI-14 Aufteilung', () => {
  const three = {
    ...BASE,
    state: 'STORED',
    cost_objects: CENTERS,
    approval_steps: [],
    blockers: ['SPLIT_INCOMPLETE'],
    split_problem: 'Die Aufteilung fehlt.',
  } as Expense;

  it('rundungsrest_und_summenkontrolle_sind_sichtbar_und_per_tastatur_bedienbar', async () => {
    const { calls, container } = setup(
      <Aufteilung expense={three} canEdit onChanged={() => undefined} />,
      'erfasser-1',
      ['receipts:write'],
      {
        correctExpense: () => ({ ...three, split_problem: null }),
      },
    );
    // gleiche Schlüsselmenge: 100,01 EUR auf drei Anteile ergibt 33,34 / 33,34 / 33,33 EUR und einen Rundungsrest von 2 Cent
    fireEvent.click(screen.getByRole('radio', { name: /Gleiche Schlüsselmenge/ }));
    for (const c of CENTERS)
      fireEvent.change(screen.getByLabelText(`Schlüsselmenge für ${c.name}`), {
        target: { value: '1' },
      });
    const table = screen.getByRole('table', { name: 'Teilbeträge' });
    const cells = within(table)
      .getAllByRole('cell')
      .map((c) => c.textContent);
    expect(cells).toContain('33,34 EUR');
    expect(cells).toContain('33,33 EUR');
    const status = screen.getByRole('status', { name: 'Summenkontrolle' });
    expect(status).toHaveTextContent(
      'Summe der Teilbeträge: 100,01 EUR von 100,01 EUR. Keine Differenz.',
    );
    expect(status).toHaveTextContent('Rundungsrest: 2 Cent');
    // speichern: Begründung ist Pflicht; der Aufruf trägt die Revision und die Schlüsselmengen als Dezimalstrings
    const save = screen.getByRole('button', { name: 'Aufteilung speichern' });
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Begründung der Aufteilung'), {
      target: { value: 'Gleiche Anteile' },
    });
    expect(save).toBeEnabled();
    save.focus();
    fireEvent.click(save);
    expect(await screen.findByText('Die Aufteilung ist gespeichert.')).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'correctExpense')?.args).toMatchObject({
      revision: 5,
      body: {
        split: {
          allocation_policy_revision: 'manuell-v1',
          reason: 'Gleiche Anteile',
          lines: [{ quantity: '1' }, { quantity: '1' }, { quantity: '1' }],
        },
      },
    });
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('differenz_in_betragsmodus_sperrt_das_speichern_und_wird_genannt', () => {
    setup(
      <Aufteilung expense={three} canEdit onChanged={() => undefined} />,
      'erfasser-1',
      ['receipts:write'],
      {},
    );
    fireEvent.click(screen.getByRole('radio', { name: 'Betrag' }));
    const amounts = ['33,34', '33,34', '33,32'];
    CENTERS.forEach((c, i) =>
      fireEvent.change(screen.getByLabelText(`Betrag für ${c.name}`), {
        target: { value: amounts[i] },
      }),
    );
    fireEvent.change(screen.getByLabelText('Begründung der Aufteilung'), {
      target: { value: 'Betragsaufteilung' },
    });
    const status = screen.getByRole('status', { name: 'Summenkontrolle' });
    expect(status).toHaveTextContent('Differenz: 0,01 EUR.');
    expect(status).toHaveTextContent('Die Teilbeträge ergeben nicht die Kostenbasis.');
    expect(screen.getByRole('button', { name: 'Aufteilung speichern' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(`Betrag für ${CENTERS[2]?.name}`), {
      target: { value: '33,33' },
    });
    expect(screen.getByRole('button', { name: 'Aufteilung speichern' })).toBeEnabled();
  });

  it('prozent_ungleich_100_sperrt_und_ohne_recht_ist_die_maske_nur_lesbar', () => {
    const view = setup(
      <Aufteilung expense={three} canEdit onChanged={() => undefined} />,
      'erfasser-1',
      ['receipts:write'],
      {},
    );
    CENTERS.forEach((c, i) =>
      fireEvent.change(screen.getByLabelText(`Anteil in Prozent für ${c.name}`), {
        target: { value: ['50', '30', '19,99'][i] },
      }),
    );
    fireEvent.change(screen.getByLabelText('Begründung der Aufteilung'), {
      target: { value: 'Prozentaufteilung' },
    });
    expect(screen.getByRole('status', { name: 'Summenkontrolle' })).toHaveTextContent(
      'Die Prozentwerte ergeben 99,99 statt 100.',
    );
    expect(screen.getByRole('button', { name: 'Aufteilung speichern' })).toBeDisabled();
    view.unmount();
    setup(
      <Aufteilung expense={three} canEdit={false} onChanged={() => undefined} />,
      'leser',
      ['receipts:read'],
      {},
    );
    expect(screen.queryByRole('button', { name: 'Aufteilung speichern' })).toBeNull();
    expect(
      screen.getByText('Ohne das Recht zum Erfassen ist die Aufteilung nur lesbar.'),
    ).toBeInTheDocument();
  });

  it('dublette_ist_erkennbar_und_per_tastatur_entscheidbar', async () => {
    const dup = {
      id: '00000000-0000-4000-8000-00000000d001',
      role: 'NEWER' as const,
      other_document_id: '00000000-0000-4000-8000-0000000000e2',
      match: 'FACTS' as const,
      certainty: 'POSSIBLE' as const,
      score: 80,
      basis: null,
      state: 'OFFEN' as const,
      detected_at: '2026-06-15T08:00:00Z',
      decided_by: null,
      decided_at: null,
      reason: null,
      other: {
        merchant: 'Tankstelle Nord',
        document_date: '2026-06-15',
        currency: 'EUR',
        amount_minor: 10001,
        document_number: 'R-1',
        source_channel: 'DRIVER_APP',
        received_at: '2026-06-15T06:00:00Z',
      },
    };
    const { calls, container } = setup(
      <Belegpruefung />,
      'pruefer-1',
      ['receipts:read', 'receipts:write', 'expenses:review'],
      {
        listExpenses: listOf([{ ...SUMMARY, state: 'IN_REVIEW' }]),
        getExpense: () => ({
          ...BASE,
          state: 'IN_REVIEW',
          duplicates: [dup],
          blockers: ['DUPLICATE_REVIEW_OPEN'],
          approvable: false,
        }),
        getReceiptExtraction: () => ({ run: {}, proposals: [], current: [] }),
        listExpenseRevisions: () => ({ items: [] }),
        decideExpenseDuplicate: () => ({
          ...BASE,
          state: 'IN_REVIEW',
          duplicates: [{ ...dup, state: 'EIGENER_VORGANG' }],
        }),
      },
    );
    fireEvent.click(await screen.findByRole('button', { name: /Prüfen: Tankstelle Nord/ }));
    const list = await screen.findByRole('list', { name: 'Dublettentreffer' });
    expect(list).toHaveTextContent('offen, Entscheidung nötig');
    expect(list).toHaveTextContent('Gegenüber: Tankstelle Nord, 2026-06-15, 100,01 EUR');
    const decide = within(list).getByRole('button', { name: 'Ist ein eigener Vorgang' });
    expect(decide).toBeDisabled(); // ohne Begründung
    fireEvent.change(screen.getByLabelText('Begründung für Entscheidung oder Quittierung'), {
      target: { value: 'Zweite Tankung am selben Tag' },
    });
    expect(decide).toBeEnabled();
    decide.focus();
    expect(document.activeElement).toBe(decide); // per Tastatur erreichbar, native Schaltfläche (Enter/Leertaste)
    fireEvent.click(decide);
    expect(await screen.findByText('Die Dublette ist entschieden.')).toBeInTheDocument();
    expect(calls.find((c) => c.id === 'decideExpenseDuplicate')?.args).toMatchObject({
      revision: 5,
      body: { decision: 'EIGENER_VORGANG' },
    });
    expect(formatViolations(await runAxe(container))).toBe('');
  });
});
