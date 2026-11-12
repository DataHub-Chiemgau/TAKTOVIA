import { HAUPTSCHRITTE } from './schritte';
import type { HauptschrittId } from './schritte';

/** Fortschrittsanzeige der Buchung in Hauptschritten (Schritt x von n); der aktuelle Schritt ist mit `aria-current="step"` markiert. */
export function Schritte({ aktuell }: { aktuell: HauptschrittId }) {
  const total = HAUPTSCHRITTE.length;
  return (
    <ol aria-label={`Buchung in ${total} Schritten`} className="tk-schritte" data-testid="schritte">
      {HAUPTSCHRITTE.map((s) => (
        <li
          key={s.id}
          data-step={s.id}
          {...(s.id === aktuell ? { 'aria-current': 'step' as const } : {})}
        >
          Schritt {s.nr} von {total}: {s.label}
        </li>
      ))}
    </ol>
  );
}
