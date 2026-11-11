import { createContext, useCallback, useContext, useId, useLayoutEffect, useState } from 'react';
import type { FormEvent, InputHTMLAttributes, ReactNode } from 'react';
import { de, useI18n } from '../i18n';
import type { MessageKey } from '../i18n';
import { fieldErrorsFromProblem } from '../problem';
import type { ProblemLike } from '../problem';
import { BusyButton } from './BusyButton';

interface FormState {
  errors: Map<string, string[]>;
  register: (pointer: string, label: string) => void;
}

const Context = createContext<FormState>({ errors: new Map(), register: () => undefined });

/** Text zu einem Verletzungscode aus dem Katalog; unbekannte Codes fallen auf eine allgemeine Meldung zurück. */
function useViolationText() {
  const { t } = useI18n();
  return (code: string) => {
    const key = `violation.${code}`;
    return t(key in de ? (key as MessageKey) : 'violation.UNKNOWN');
  };
}

export interface FormProps {
  children: ReactNode;
  onSubmit: () => unknown;
  submitLabel: string;
  /** Fehler der letzten Antwort; Feldfehler werden anhand von `violations[].pointer` zugeordnet. */
  problem?: ProblemLike | undefined;
}

/**
 * Formular mit Feldfehlern aus der API. Die Zuordnung nutzt nur `pointer` und `code`; der freie Fehlertext des Servers wird nie
 * angezeigt. Verletzungen ohne passendes Feld erscheinen in der Zusammenfassung, damit nichts verloren geht.
 */
export function Form({ children, onSubmit, submitLabel, problem }: FormProps) {
  const { t } = useI18n();
  const violationText = useViolationText();
  const errors = fieldErrorsFromProblem(problem);
  const [labels, setLabels] = useState<ReadonlyMap<string, string>>(new Map());
  const register = useCallback((pointer: string, label: string) => {
    setLabels((old) => {
      if (old.get(pointer) === label) return old;
      return new Map(old).set(pointer, label);
    });
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
  };

  return (
    <Context.Provider value={{ errors, register }}>
      <form onSubmit={submit} noValidate>
        <FormSummary errors={errors} labels={labels} violationText={violationText} t={t} />
        {children}
        <BusyButton onAction={onSubmit}>{submitLabel}</BusyButton>
      </form>
    </Context.Provider>
  );
}

function FormSummary({
  errors,
  labels,
  violationText,
  t,
}: {
  errors: Map<string, string[]>;
  labels: ReadonlyMap<string, string>;
  violationText: (code: string) => string;
  t: ReturnType<typeof useI18n>['t'];
}) {
  if (errors.size === 0) return null;
  // Verknüpfung zum Feld per Anker-ID `tk-field-<pointer>`; die Bezeichnung melden die Felder per Layout-Effekt.
  return (
    <div role="alert" className="tk-form-summary">
      <p>{t('common.problemsInForm')}</p>
      <ul>
        {[...errors].map(([pointer, codes]) => (
          <li key={pointer}>
            <a
              href={`#${fieldAnchor(pointer)}`}
              onClick={(event) => {
                const target = document.getElementById(fieldAnchor(pointer));
                if (target) {
                  event.preventDefault();
                  target.focus();
                }
              }}
            >
              {labels.get(pointer) ?? t('common.otherViolation', { pointer })}
            </a>
            : {codes.map((c) => violationText(c)).join(' ')}
          </li>
        ))}
      </ul>
    </div>
  );
}

export function fieldAnchor(pointer: string): string {
  return `tk-field-${pointer.replace(/[^A-Za-z0-9_-]/g, '_')}`;
}

export interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  /** JSON-Pointer des Feldes im Request-Body, z. B. `/legal_name`; muss zu `violations[].pointer` passen. */
  pointer: string;
  label: string;
  hint?: string;
}

export function Field({ pointer, label, hint, ...input }: FieldProps) {
  const form = useContext(Context);
  const violationText = useViolationText();
  const uid = useId();
  const id = fieldAnchor(pointer);
  const codes = form.errors.get(pointer);
  const { register } = form;
  useLayoutEffect(() => register(pointer, label), [register, pointer, label]);
  const errorId = `${uid}-error`;
  const hintId = `${uid}-hint`;
  const described = [codes ? errorId : '', hint ? hintId : ''].filter(Boolean).join(' ');
  return (
    <div className="tk-field">
      <label htmlFor={id}>{label}</label>
      {hint ? (
        <span id={hintId} className="tk-field-hint">
          {hint}
        </span>
      ) : null}
      <input
        {...input}
        id={id}
        name={input.name ?? pointer.slice(1)}
        aria-invalid={codes ? 'true' : undefined}
        aria-describedby={described || undefined}
      />
      {codes ? (
        <p id={errorId} className="tk-field-error">
          {codes.map((c) => violationText(c)).join(' ')}
        </p>
      ) : null}
    </div>
  );
}
