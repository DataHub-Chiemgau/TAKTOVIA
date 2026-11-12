import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { tx } from '../texte';

/** Position des Geräts für genau eine Suche; wird nie gespeichert (kein Storage, kein Cookie, keine Anfrage außer der Ortssuche). */
export interface DevicePosition {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
}

/** Höchste Ungenauigkeit (Meter), bei der ein Standort noch als Startvorschlag angeboten wird (Planungsdefault). */
export const MAX_ACCURACY_M = 500;
/** Zeit ohne Antwort (inklusive der Wartezeit auf die Browserabfrage), danach „Zeitüberschreitung“. */
export const LOCATION_TIMEOUT_MS = 15_000;

type State =
  | { kind: 'idle' }
  | { kind: 'dialog' }
  | { kind: 'requesting' }
  | { kind: 'suggestion'; position: DevicePosition }
  | { kind: 'failed'; reason: 'denied' | 'blocked' | 'unavailable' | 'timeout' | 'imprecise' };

const FAILURE_TEXT = {
  denied: 'standort.abgelehnt',
  blocked: 'standort.blockiert',
  unavailable: 'standort.nichtVerfuegbar',
  timeout: 'standort.zeit',
  imprecise: 'standort.ungenau',
} as const;

/** Browserstatus „blockiert“: die Berechtigung steht auf `denied` (kein erneutes Nachfragen möglich). Die Abfrage löst keine Standortabfrage aus. */
async function permissionDenied(): Promise<boolean> {
  try {
    const status = await navigator.permissions?.query({ name: 'geolocation' });
    return status?.state === 'denied';
  } catch {
    return false;
  }
}

/**
 * Standortfreigabe nur nach aktiver Zustimmung (WP-M2-39, LH-6.3-PAX-003). Die Standortabfrage des Browsers wird ausschließlich durch die Schaltfläche „Meinen Standort
 * verwenden“ und die Bestätigung im Dialog ausgelöst, nie beim Seitenaufruf. Ergebnis ist nur ein Startvorschlag, der bestätigt werden muss; die manuelle Ortswahl bleibt in
 * jedem Zustand sichtbar (diese Komponente verändert sie nie). Trifft eine Position ein, nachdem der Fahrgast schon einen Start gewählt hat ([startChosen]), oder nach Abbruch
 * oder Verlassen der Seite, wird sie verworfen (kein Überschreiben, kein Vorschlag ohne neue Bestätigung). Nichts wird gespeichert.
 */
export function StandortFreigabe({
  startChosen,
  onConfirm,
  onManual,
}: {
  startChosen: boolean;
  /** Der Fahrgast hat den Vorschlag bestätigt: nur jetzt wird die Position weitergegeben (als Suchkoordinate der aktuellen Anfrage). */
  onConfirm: (position: DevicePosition) => void;
  onManual?: () => void;
}) {
  const [state, setState] = useState<State>({ kind: 'idle' });
  const attempt = useRef(0);
  const trigger = useRef<HTMLButtonElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const startChosenRef = useRef(startChosen);
  useEffect(() => {
    startChosenRef.current = startChosen;
  }, [startChosen]);
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator;

  // Verlassen der Seite: laufende Abfrage gilt als abgebrochen, ein späteres Ergebnis wird verworfen.
  useEffect(
    () => () => {
      attempt.current++;
    },
    [],
  );
  useEffect(() => {
    if (state.kind === 'dialog') confirmButton.current?.focus();
  }, [state.kind]);

  if (!supported)
    return (
      <p role="status" data-testid="standort-hinweis">
        {tx('standort.keineUnterstuetzung')}
      </p>
    );

  const close = () => {
    attempt.current++;
    setState({ kind: 'idle' });
    trigger.current?.focus();
  };

  const request = () => {
    const mine = ++attempt.current;
    const fail = (reason: 'denied' | 'blocked' | 'unavailable' | 'timeout' | 'imprecise') => {
      if (mine === attempt.current) setState({ kind: 'failed', reason });
    };
    setState({ kind: 'requesting' });
    const timer = setTimeout(() => fail('timeout'), LOCATION_TIMEOUT_MS);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        clearTimeout(timer);
        if (mine !== attempt.current) return; // abgebrochen oder Seite verlassen
        if (startChosenRef.current) {
          // Der Fahrgast hat inzwischen selbst einen Start gewählt: nichts überschreiben, nichts vorschlagen.
          setState({ kind: 'idle' });
          return;
        }
        if (p.coords.accuracy > MAX_ACCURACY_M) return fail('imprecise');
        setState({
          kind: 'suggestion',
          position: {
            latitude: p.coords.latitude,
            longitude: p.coords.longitude,
            accuracyMeters: Math.round(p.coords.accuracy),
          },
        });
      },
      (e) => {
        clearTimeout(timer);
        if (mine !== attempt.current) return;
        if (e.code === 1)
          void permissionDenied().then((blocked) => fail(blocked ? 'blocked' : 'denied'));
        else if (e.code === 3) fail('timeout');
        else fail('unavailable');
      },
      { enableHighAccuracy: false, timeout: LOCATION_TIMEOUT_MS - 1000, maximumAge: 0 },
    );
  };

  /** Tab bleibt im Dialog (zwei Schaltflächen); Escape schließt. */
  const dialogKeys = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      close();
    } else if (e.key === 'Tab') {
      const first = confirmButton.current;
      const last = cancelButton.current;
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last?.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first?.focus();
      }
    }
  };

  return (
    <div className="tk-standort">
      <button
        ref={trigger}
        type="button"
        onClick={() => setState({ kind: 'dialog' })}
        disabled={state.kind === 'requesting'}
      >
        {tx('standort.button')}
      </button>
      {state.kind === 'dialog' ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="standort-titel"
          aria-describedby="standort-text"
          onKeyDown={dialogKeys}
        >
          <h2 id="standort-titel">{tx('standort.dialog.titel')}</h2>
          <p id="standort-text">{tx('standort.dialog.text')}</p>
          <button ref={confirmButton} type="button" onClick={request}>
            {tx('standort.dialog.ja')}
          </button>
          <button ref={cancelButton} type="button" onClick={close}>
            {tx('standort.dialog.nein')}
          </button>
        </div>
      ) : null}
      {state.kind === 'requesting' ? (
        <p role="status" aria-busy="true">
          {tx('standort.laeuft')}
        </p>
      ) : null}
      {state.kind === 'suggestion' ? (
        <div role="status" data-testid="standort-vorschlag">
          <p>{`${tx('standort.vorschlag')} (±${state.position.accuracyMeters} m)`}</p>
          <button
            type="button"
            onClick={() => {
              onConfirm(state.position);
              setState({ kind: 'idle' });
            }}
          >
            {tx('standort.uebernehmen')}
          </button>
          <button type="button" onClick={() => setState({ kind: 'idle' })}>
            {tx('standort.verwerfen')}
          </button>
        </div>
      ) : null}
      {state.kind === 'failed' ? (
        <div role="status" data-testid="standort-hinweis">
          <p>{tx(FAILURE_TEXT[state.reason])}</p>
          {onManual ? (
            <button type="button" onClick={onManual}>
              {tx('standort.manuell')}
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
