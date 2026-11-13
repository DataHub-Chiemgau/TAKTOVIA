import type { RideRejectionCode } from '@taktovia/api-client';
import { tx } from '../texte';
import type { TextKey } from '../texte';

/** Alle Codes, für die die Oberfläche einen Textbaustein hat (Spiegel von `RideRejectionCode`; der Typ erzwingt Vollständigkeit). */
export const REJECTION_CODES: readonly RideRejectionCode[] = [
  'LEAD_TIME',
  'HORIZON',
  'WINDOW_NOT_FEASIBLE',
  'OUTSIDE_HOURS',
  'OUTSIDE_AREA',
  'RULE_FORBIDDEN',
  'NO_STOP_IN_RANGE',
  'NO_ACCESSIBLE_STOP',
  'NO_ROUTE',
  'EQUIPMENT_MISSING',
  'NO_STAFF',
  'NO_CAPACITY',
  'NO_TARIFF',
  'MAX_WAIT_EXCEEDED',
  'NO_VEHICLE',
  'SHIFT_ENDED',
  'MAX_DETOUR_EXCEEDED',
  'PARTNER_UNAVAILABLE',
];

/** `LEAD_TIME` -> `leadTime` (Schlüssel des Textkatalogs sind camelCase). */
const key = (code: RideRejectionCode): string =>
  code
    .toLowerCase()
    .split('_')
    .map((part, i) => (i === 0 ? part : part.charAt(0).toUpperCase() + part.slice(1)))
    .join('');

/**
 * Ablehnung einer Fahrtanfrage für UI-01 (WP-M2-13): Begründung und nächste Handlung als feste Textbausteine je Grund. Es erscheint nur der Primärgrund; Sekundärgründe
 * werden dem Fahrgast nicht gezeigt (ein Grund, eine Handlung). Angesagt als Status, nicht nur farbig.
 */
export function RejectionMessage({ code }: { code: RideRejectionCode }) {
  return (
    <div role="status" className="tk-rejection" data-testid="rejection" data-code={code}>
      <p>{tx(`rej.${key(code)}.text` as TextKey)}</p>
      <p>
        <strong>{tx('rej.next')}</strong> {tx(`rej.${key(code)}.next` as TextKey)}
      </p>
    </div>
  );
}
