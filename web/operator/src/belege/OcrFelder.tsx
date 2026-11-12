import { ApiError, useApi } from '@taktovia/api-client';
import type {
  ExtractedField,
  Expense,
  ExpenseRevision,
  ExtractionResult,
} from '@taktovia/api-client';
import { StateView, StatusBadge } from '@taktovia/design-system';
import { useEffect, useState } from 'react';
import { formatMinor } from './betrag';

const FIELD_LABEL: Record<string, string> = {
  DOCUMENT_DATE: 'Belegdatum',
  AMOUNT: 'Brutto',
  CURRENCY: 'Währung',
  MERCHANT: 'Händler',
  DOCUMENT_NUMBER: 'Belegnummer',
  VAT_AMOUNT: 'Steuer',
  NET_AMOUNT: 'Netto',
};
const STATUS_LABEL: Record<string, string> = {
  PROPOSED: 'Vorschlag',
  CONFIRMED: 'bestätigt',
  CORRECTED: 'korrigiert',
};
const FLAG_LABEL: Record<string, string> = {
  LOW_CONFIDENCE: 'niedrige Konfidenz',
  SUM_MISMATCH: 'Summenabweichung',
};
const FIELD_NAME: Record<string, string> = {
  amount_minor: 'Brutto',
  currency: 'Währung',
  document_date: 'Belegdatum',
  merchant: 'Händler',
  document_number: 'Belegnummer',
  vat_amounts: 'Steuer',
  net_amount_minor: 'Netto',
  cost_assignment: 'Kostenzuordnung',
  split: 'Aufteilung',
  fuel: 'Tankwerte',
};

const show = (v: unknown): string =>
  v === null || v === undefined ? '–' : typeof v === 'object' ? JSON.stringify(v) : String(v);

/**
 * UI-14 OCR-Felder (WP-M4-27/-28/-38): rechts neben dem Original stehen die erfassten Feldwerte, die OCR-Vorschläge mit **Konfidenz** und Markierung (niedrige Konfidenz, Summenabweichung) und die
 * **Korrekturhistorie** (alle Revisionen mit Alt- und Neuwert, Bearbeiter und Grund). Ein OCR-Vorschlag ist nie Belegstand: nur Bestätigtes oder Korrigiertes zählt (Bestätigung geschieht über die
 * Korrektur des Belegs, nicht in dieser Ansicht).
 */
export function OcrFelder({ expense }: { expense: Expense }) {
  const api = useApi();
  // Ergebnisse gehören zu Beleg und Revision; für einen anderen Stand gilt wieder „lädt“ (kein Zustand in der Wirkung)
  const key = `${expense.document_id}:${expense.revision}`;
  const [extractionResult, setExtraction] = useState<
    { key: string; value: ExtractionResult | 'none' | 'error' } | undefined
  >();
  const [historyResult, setHistory] = useState<
    { key: string; value: ExpenseRevision[] | 'error' } | undefined
  >();
  const extraction = extractionResult?.key === key ? extractionResult.value : undefined;
  const history = historyResult?.key === key ? historyResult.value : undefined;

  useEffect(() => {
    let cancelled = false;
    api
      .call('getReceiptExtraction', { path: { document_id: expense.document_id } })
      .then((r) => !cancelled && setExtraction({ key, value: r }))
      .catch(
        (e: unknown) =>
          !cancelled &&
          setExtraction({
            key,
            value: e instanceof ApiError && e.status === 404 ? 'none' : 'error',
          }),
      );
    api
      .call('listExpenseRevisions', { path: { expense_id: expense.document_id } })
      .then((r) => !cancelled && setHistory({ key, value: r.items }))
      .catch(() => !cancelled && setHistory({ key, value: 'error' }));
    return () => {
      cancelled = true;
    };
  }, [api, expense.document_id, expense.revision, key]);

  const currency = typeof expense.fields.currency === 'string' ? expense.fields.currency : 'EUR';
  const shown = (f: ExtractedField): string =>
    f.field === 'AMOUNT' || f.field === 'NET_AMOUNT' || f.field === 'VAT_AMOUNT'
      ? f.normalized_value !== null && /^-?\d+$/.test(f.normalized_value)
        ? formatMinor(BigInt(f.normalized_value), currency)
        : (f.display_value ?? '–')
      : (f.display_value ?? f.normalized_value ?? '–');

  return (
    <section aria-label="OCR-Felder">
      <h3>Felder</h3>
      <dl>
        <dt>Händler</dt>
        <dd>{show(expense.fields.merchant)}</dd>
        <dt>Belegdatum</dt>
        <dd>{show(expense.fields.document_date)}</dd>
        <dt>Belegnummer</dt>
        <dd>{show(expense.fields.document_number)}</dd>
        <dt>Brutto</dt>
        <dd>
          {typeof expense.fields.amount_minor === 'number'
            ? formatMinor(expense.fields.amount_minor, currency)
            : '–'}
        </dd>
        <dt>Steuergruppe</dt>
        <dd>
          {(expense.fields.vat_amounts ?? []).length === 0
            ? '–'
            : (expense.fields.vat_amounts ?? [])
                .map((v) => `${v.vat_rate_pct} %: ${formatMinor(v.amount_minor, currency)}`)
                .join('; ')}
        </dd>
      </dl>
      {expense.unconfirmed_fields.length > 0 && (
        <p role="status">
          <StatusBadge tone="warning">Offen</StatusBadge> Nicht bestätigt:{' '}
          {expense.unconfirmed_fields.map((f) => FIELD_NAME[f] ?? f).join(', ')}.
        </p>
      )}
      {expense.sum_mismatch && (
        <p role="alert">
          <StatusBadge tone="error">Summenabweichung</StatusBadge> Netto und Steuer ergeben nicht
          den Bruttobetrag.
        </p>
      )}

      <h4>OCR-Vorschläge mit Konfidenz</h4>
      {extraction === undefined && <StateView kind="loading" level={2} />}
      {extraction === 'none' && <p>Für diesen Beleg liegt keine Texterkennung vor.</p>}
      {extraction === 'error' && <p role="alert">Die Texterkennung konnte nicht geladen werden.</p>}
      {extraction && typeof extraction === 'object' && (
        <table aria-label="OCR-Vorschläge">
          <thead>
            <tr>
              <th scope="col">Feld</th>
              <th scope="col">Wert</th>
              <th scope="col">Konfidenz</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {extraction.current.map((f) => (
              <tr key={f.id}>
                <th scope="row">{FIELD_LABEL[f.field] ?? f.field}</th>
                <td>{shown(f)}</td>
                <td>{f.confidence === null ? '–' : `${Math.round(f.confidence * 100)} %`}</td>
                <td>
                  {STATUS_LABEL[f.status] ?? f.status}
                  {f.flag_reason ? ` (${FLAG_LABEL[f.flag_reason] ?? f.flag_reason})` : ''}
                  {f.corrects_field_id ? ' – korrigiert' : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>Korrekturhistorie</h4>
      {history === undefined && <StateView kind="loading" level={2} />}
      {history === 'error' && (
        <p role="alert">Die Korrekturhistorie konnte nicht geladen werden.</p>
      )}
      {Array.isArray(history) && (
        <ol aria-label="Korrekturhistorie">
          {history.map((r) => (
            <li key={r.revision}>
              Revision {r.revision}: {r.reason} ({r.edited_by}
              {Object.entries(r.fields).length > 0
                ? '; ' +
                  Object.entries(r.fields)
                    .map(([k, c]) => {
                      const change = c as { old?: unknown; new?: unknown };
                      const amount = k === 'amount_minor' || k === 'net_amount_minor';
                      const fmt = (v: unknown) =>
                        amount && typeof v === 'number' ? formatMinor(v, currency) : show(v);
                      return `${FIELD_NAME[k] ?? k}: ${fmt(change.old)} → ${fmt(change.new)}`;
                    })
                    .join('; ')
                : ''}
              )
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
