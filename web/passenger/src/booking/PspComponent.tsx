import { useId } from 'react';
import { tx } from '../texte';

/**
 * Schnittstelle zur PSP-gehosteten Zahlungskomponente (WP-M2-42, SEC-008): Die Anwendung sieht nie Kartendaten, sie erhält vom PSP-Baustein nur einen Verweis
 * auf das gewählte Zahlungsmittel (`payment_method_ref`). Die Produktivkomponente (D-03) ersetzt [SimulatorPsp]; beide erfüllen diese Schnittstelle.
 */
export interface PspComponentProps {
  /** Gewähltes Zahlungsmittel als PSP-Verweis; `null`, solange nichts gewählt ist. */
  onChange: (paymentMethodRef: string | null) => void;
  value: string | null;
  disabled?: boolean;
}

export type PspComponent = (props: PspComponentProps) => React.ReactNode;

/** Zahlungsmittel des PSP-Simulators (Testmodus, nie produktiv): Verweise nach `PspAdapterSimulatorTest`. */
export const SIMULATOR_METHODS = [
  { ref: 'pm_ok', label: 'Testzahlungsmittel: Autorisierung gelingt' },
  { ref: 'pm_sca', label: 'Testzahlungsmittel: Zusatzbestätigung (SCA) erforderlich' },
  { ref: 'pm_decline', label: 'Testzahlungsmittel: wird abgelehnt' },
] as const;

/** Zahlungsauswahl des Simulators (D-03: Bau gegen Simulator, blockiert nur die Produktivschaltung). Ein Auswahlfeld ohne jede Dateneingabe. */
export const SimulatorPsp: PspComponent = ({ onChange, value, disabled }) => {
  const name = useId();
  return (
    <fieldset disabled={disabled} data-testid="psp-auswahl">
      <legend>{tx('checkout.zahlung')}</legend>
      <p>{tx('checkout.pspHinweis')}</p>
      {SIMULATOR_METHODS.map((m) => (
        <label key={m.ref} style={{ display: 'block' }}>
          <input
            type="radio"
            name={name}
            value={m.ref}
            checked={value === m.ref}
            onChange={() => onChange(m.ref)}
          />{' '}
          {m.label}
        </label>
      ))}
    </fieldset>
  );
};
