import { ApiError, useApi } from '@taktovia/api-client';
import type { CostObject, Expense } from '@taktovia/api-client';
import { StatusBadge } from '@taktovia/design-system';
import { useMemo, useState } from 'react';
import {
  HUNDRED_PERCENT,
  decimalString,
  formatMinor,
  parseDecimal,
  parseMoney,
  verteile,
} from './betrag';

type Mode = 'PERCENT' | 'AMOUNT' | 'WEIGHT';

const MODE_LABEL: Record<Mode, string> = {
  PERCENT: 'Prozent',
  AMOUNT: 'Betrag',
  WEIGHT: 'Gleiche Schlüsselmenge',
};

/** Kostenbasis des Belegs wie im Posting (Testregel D-06: Vorsteuer voll abziehbar): Brutto minus Steuerbeträge. `undefined`, solange der Betrag fehlt. */
export function kostenbasis(expense: Expense): { minor: bigint; currency: string } | undefined {
  const amount = expense.fields.amount_minor;
  const currency = expense.fields.currency;
  if (typeof amount !== 'number' || typeof currency !== 'string') return undefined;
  const vat = (expense.fields.vat_amounts ?? []).reduce((a, v) => a + BigInt(v.amount_minor), 0n);
  return { minor: BigInt(amount) - vat, currency };
}

const label = (o: CostObject) => o.name ?? o.reference;

const toText = (scaled: bigint | undefined, fallback = '') =>
  scaled === undefined ? fallback : decimalString(scaled).replace('.', ',');

/**
 * UI-14 Aufteilung (WP-M4-35/-38, DOC-011): verteilt die Kostenbasis eines Belegs mit mehreren zugeordneten Kostenobjekten nach Prozent, Betrag oder gleicher Schlüsselmenge. Die Summenkontrolle zeigt
 * Summe, Belegbetrag und Differenz, der Rundungsrest nennt Cent und Empfänger (Largest Remainder wie der Server). Änderungen am Plan sind nur im Belegzustand `STORED` und mit Scope `receipts:write`
 * möglich; sonst ist die Maske eine Lesesicht. Der Server prüft jede Aufteilung erneut (422 `SPLIT_INCOMPLETE` bei Lücke oder Differenz).
 */
export function Aufteilung({
  expense,
  canEdit,
  onChanged,
  onConflict,
}: {
  expense: Expense;
  canEdit: boolean;
  onChanged: (e: Expense) => void;
  onConflict?: () => void;
}) {
  const api = useApi();
  const objects = expense.cost_objects;
  const basis = kostenbasis(expense);
  const plan = expense.split;
  const [mode, setMode] = useState<Mode>(plan?.mode ?? 'PERCENT');
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      objects.map((o) => {
        const line = plan?.lines.find((l) => l.cost_object_id === o.id);
        const text =
          line?.percent != null
            ? line.percent.replace('.', ',')
            : line?.amount_minor != null
              ? formatMinor(line.amount_minor, basis?.currency ?? 'EUR').replace(/ [A-Z]{3}$/, '')
              : line?.quantity != null
                ? line.quantity.replace('.', ',')
                : mode === 'WEIGHT'
                  ? '1'
                  : '';
        return [o.id, text];
      }),
    ),
  );
  const [policy, setPolicy] = useState(plan?.allocation_policy_revision ?? 'manuell-v1');
  const [reason, setReason] = useState(plan?.reason ?? '');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | undefined>();

  const calc = useMemo(() => {
    if (!basis) return { valid: false, problem: 'Der Belegbetrag fehlt.' } as const;
    const rows = objects.map((o) => ({ o, text: values[o.id] ?? '' }));
    if (mode === 'AMOUNT') {
      const parsed = rows.map((r) => parseMoney(r.text));
      if (parsed.some((p) => p === undefined))
        return { valid: false, problem: 'Mindestens ein Betrag ist keine gültige Zahl.' } as const;
      const betraege = parsed as bigint[];
      const summe = betraege.reduce((a, b) => a + b, 0n);
      return {
        valid: summe === basis.minor,
        betraege,
        summe,
        differenz: basis.minor - summe,
        restcent: 0,
        restEmpfaenger: [] as string[],
        problem:
          summe === basis.minor ? undefined : 'Die Teilbeträge ergeben nicht die Kostenbasis.',
      } as const;
    }
    const parsed = rows.map((r) => parseDecimal(r.text));
    if (parsed.some((p) => p === undefined || (mode === 'WEIGHT' && p === 0n)))
      return {
        valid: false,
        problem:
          mode === 'PERCENT'
            ? 'Mindestens ein Prozentwert ist keine gültige Zahl.'
            : 'Jede Schlüsselmenge muss größer als 0 sein.',
      } as const;
    const gewichte = parsed as bigint[];
    const gewichtSumme = gewichte.reduce((a, b) => a + b, 0n);
    const percentOk = mode === 'WEIGHT' || gewichtSumme === HUNDRED_PERCENT;
    const v = verteile(
      basis.minor,
      rows.map((r, i) => ({ id: r.o.id, gewicht: gewichte[i] ?? 0n })),
    );
    if (!v) return { valid: false, problem: 'Die Summe der Gewichte ist 0.' } as const;
    return {
      valid: percentOk,
      betraege: v.betraege,
      summe: v.betraege.reduce((a, b) => a + b, 0n),
      differenz: 0n,
      restcent: v.restcent,
      restEmpfaenger: v.restEmpfaenger,
      prozentSumme: gewichtSumme,
      problem: percentOk
        ? undefined
        : `Die Prozentwerte ergeben ${toText(gewichtSumme)} statt 100.`,
    } as const;
  }, [basis, mode, objects, values]);

  const editable = canEdit && expense.state === 'STORED' && objects.length >= 2;
  const reasonOk = reason.trim().length >= 3 && policy.trim().length >= 1;

  if (objects.length < 2)
    return (
      <section aria-label="Aufteilung">
        <h3>Aufteilung</h3>
        <p>
          Der Beleg ist {objects.length === 0 ? 'keinem' : 'einem'} Kostenobjekt zugeordnet; eine
          Aufteilung ist erst bei mehreren Kostenobjekten nötig.
        </p>
      </section>
    );

  const save = () => {
    if (!basis || !calc.valid) return;
    setBusy(true);
    setNotice(undefined);
    const lines = objects.map((o) => {
      const text = values[o.id] ?? '';
      const base = { cost_object_type: o.kind, cost_object_id: o.id } as const;
      if (mode === 'AMOUNT')
        return {
          ...base,
          amount: {
            amount_minor: Number(parseMoney(text) ?? 0n),
            currency: basis.currency,
            exponent: 2,
          },
        };
      if (mode === 'PERCENT') return { ...base, percent: decimalString(parseDecimal(text) ?? 0n) };
      return { ...base, quantity: decimalString(parseDecimal(text) ?? 0n) };
    });
    api
      .call('correctExpense', {
        path: { expense_id: expense.document_id },
        revision: expense.revision,
        body: {
          split: { allocation_policy_revision: policy.trim(), reason: reason.trim(), lines },
        },
      })
      .then((next) => {
        onChanged(next);
        setNotice({ tone: 'ok', text: 'Die Aufteilung ist gespeichert.' });
      })
      .catch((e: unknown) => {
        const status = e instanceof ApiError ? e.status : 0;
        if (status === 412 || status === 428) onConflict?.();
        setNotice({
          tone: 'error',
          text:
            status === 412 || status === 428
              ? 'Konflikt (412): Der Beleg wurde zwischenzeitlich geändert. Bitte den aktuellen Stand laden.'
              : status === 409
                ? 'Der Beleg ist nicht mehr in Erfassung; die Aufteilung ist gesperrt (409).'
                : status === 422
                  ? 'Die Aufteilung wurde abgewiesen (422): Summe, Prozent oder Kostenobjekte prüfen.'
                  : 'Die Aufteilung konnte nicht gespeichert werden.',
        });
      })
      .finally(() => setBusy(false));
  };

  return (
    <section aria-label="Aufteilung">
      <h3>Aufteilung auf Kostenobjekte</h3>
      {expense.split_problem ? (
        <p role="alert">
          <StatusBadge tone="error">Unvollständig</StatusBadge> {expense.split_problem}
        </p>
      ) : (
        <p>
          <StatusBadge tone="ok">{plan ? 'Vollständig' : 'Nicht nötig'}</StatusBadge>
        </p>
      )}
      {basis && (
        <p>
          Kostenbasis des Belegs: <strong>{formatMinor(basis.minor, basis.currency)}</strong>{' '}
          (Brutto abzüglich abziehbarer Steuer, Testregel).
        </p>
      )}
      <fieldset disabled={!editable}>
        <legend>Art der Aufteilung</legend>
        {(Object.keys(MODE_LABEL) as Mode[]).map((m) => (
          <label key={m}>
            <input
              type="radio"
              name="aufteilung-art"
              checked={mode === m}
              onChange={() => {
                setMode(m);
                setValues(
                  Object.fromEntries(objects.map((o) => [o.id, m === 'WEIGHT' ? '1' : ''])),
                );
              }}
            />{' '}
            {MODE_LABEL[m]}
          </label>
        ))}
      </fieldset>
      <table aria-label="Teilbeträge">
        <thead>
          <tr>
            <th scope="col">Kostenobjekt</th>
            <th scope="col">
              {mode === 'AMOUNT' ? 'Betrag' : mode === 'PERCENT' ? 'Anteil in %' : 'Schlüsselmenge'}
            </th>
            <th scope="col">Teilbetrag</th>
          </tr>
        </thead>
        <tbody>
          {objects.map((o, i) => (
            <tr key={o.id}>
              <th scope="row">{label(o)}</th>
              <td>
                <input
                  type="text"
                  inputMode="decimal"
                  aria-label={`${mode === 'AMOUNT' ? 'Betrag' : mode === 'PERCENT' ? 'Anteil in Prozent' : 'Schlüsselmenge'} für ${label(o)}`}
                  value={values[o.id] ?? ''}
                  disabled={!editable}
                  onChange={(e) => setValues({ ...values, [o.id]: e.target.value })}
                />
              </td>
              <td>
                {calc.betraege && basis ? formatMinor(calc.betraege[i] ?? 0n, basis.currency) : '–'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div role="status" aria-live="polite" aria-label="Summenkontrolle">
        {basis && calc.summe !== undefined && (
          <p>
            Summe der Teilbeträge: {formatMinor(calc.summe, basis.currency)} von{' '}
            {formatMinor(basis.minor, basis.currency)}.{' '}
            {calc.differenz !== undefined && calc.differenz !== 0n
              ? `Differenz: ${formatMinor(calc.differenz, basis.currency)}.`
              : 'Keine Differenz.'}
          </p>
        )}
        {calc.restcent !== undefined && calc.restcent > 0 && (
          <p>
            Rundungsrest: {calc.restcent} Cent, nach dem größten Dezimalrest an{' '}
            {calc.restEmpfaenger
              .map((id) =>
                label(
                  objects.find((o) => o.id === id) ?? ({ name: null, reference: id } as CostObject),
                ),
              )
              .join(' und ')}
            .
          </p>
        )}
        {calc.problem && <p>{calc.problem}</p>}
      </div>
      {editable && (
        <>
          <div>
            <label htmlFor="aufteilung-schluessel">Umlageschlüssel (Revision)</label>
            <input
              id="aufteilung-schluessel"
              type="text"
              value={policy}
              maxLength={256}
              onChange={(e) => setPolicy(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="aufteilung-grund">Begründung der Aufteilung</label>
            <input
              id="aufteilung-grund"
              type="text"
              value={reason}
              maxLength={1000}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
          <button type="button" disabled={busy || !calc.valid || !reasonOk} onClick={save}>
            Aufteilung speichern
          </button>
        </>
      )}
      {!editable && (
        <p>
          {expense.state === 'STORED'
            ? 'Ohne das Recht zum Erfassen ist die Aufteilung nur lesbar.'
            : 'Die Aufteilung ist nur in der Erfassung änderbar.'}
        </p>
      )}
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={notice.tone === 'error' ? 'error' : 'ok'}>
            {notice.tone === 'error' ? 'Fehler' : 'Erledigt'}
          </StatusBadge>{' '}
          {notice.text}
        </p>
      )}
    </section>
  );
}
