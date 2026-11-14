import type { ActionAvailability } from '@taktovia/design-system';
import { StateView } from '@taktovia/design-system';
import { tx } from '../texte';

/**
 * Offline nicht verfügbare Funktion (WP-M5-09 AK 3): nennt Grund und nächsten Schritt, statt eine Schaltfläche still zu deaktivieren oder wegzulassen. Keine Buchungs- oder
 * Zahlungsaktion, kein vorgetäuschter Erfolg.
 */
export function GesperrteAktion({ availability }: { availability: ActionAvailability }) {
  return (
    <div data-testid="aktion-gesperrt">
      <StateView
        kind="offline"
        title={tx('offline.title')}
        hint={`${availability.reason} ${availability.nextStep}`.trim() || tx('offline.hint')}
      />
    </div>
  );
}
