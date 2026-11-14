import type { InspectionStatus, OperatorVehicle } from '@taktovia/api-client';
import type { StatusTone } from '@taktovia/design-system';
import { useAnnounce } from '@taktovia/design-system';
import { useEffect, useRef } from 'react';

/**
 * Prüfstatus je (Fahrzeug, Schicht) in der Leitstelle (UI-07, WP-M3-23, LH-6.21-CHK-016): genau die sechs Werte des Servers, immer mit Text **und** Symbol (nie nur Farbe, Kap. 06 §6.1).
 * Die Ableitung steht allein im Server (`vertraege/m3-pruefstatus.md`); die Anzeige ist keine Rechtequelle und leitet nichts selbst ab.
 */
export const INSPECTION_STATUS: Readonly<
  Record<InspectionStatus, { label: string; icon: string; tone: StatusTone }>
> = {
  PENDING: { label: 'Ausstehend', icon: '○', tone: 'neutral' },
  IN_PROGRESS: { label: 'In Bearbeitung', icon: '◐', tone: 'info' },
  PASSED: { label: 'Bestanden', icon: '✓', tone: 'ok' },
  RESTRICTED: { label: 'Eingeschränkt', icon: '▲', tone: 'warning' },
  BLOCKED: { label: 'Gesperrt', icon: '✕', tone: 'error' },
  UNCLEAR: { label: 'Technisch ungeklärt', icon: '?', tone: 'warning' },
};

/**
 * Aktualisierungsintervall der Prüfstatus-Anzeige in Millisekunden. Der Wert ist eine Planungsannahme (OP-05: Intervall und Technik Push/Polling sind im Pflichtenheft nicht festgelegt; vor der
 * Abnahme-Zeitmessung zu entscheiden): der Kanal (WP-M3-35) stößt das Nachladen sofort an, zusätzlich lädt die Ansicht in diesem Takt, weil nicht jeder Statuswechsel ein Kanalereignis hat
 * (z. B. Start eines Prüflaufs).
 */
export const INSPECTION_REFRESH_MS = 15_000;

/** Statusanzeige mit Text und Symbol; bei möglicherweise veraltetem Stand (Kanal unterbrochen) mit Kennzeichnung. */
export function InspectionStatusBadge({
  status,
  stale = false,
}: {
  status: InspectionStatus;
  stale?: boolean;
}) {
  const s = INSPECTION_STATUS[status];
  return (
    <span className="tk-status" data-tone={s.tone} data-inspection-status={status}>
      <span data-icon aria-hidden="true">
        {s.icon}
      </span>
      <span>{s.label}</span>
      {stale ? <span data-stale="true"> · möglicherweise veraltet</span> : null}
    </span>
  );
}

/**
 * Übernahme einer neu geladenen Seite: je Fahrzeug gilt die **höhere** Statusrevision. Eine ältere Antwort (zwei Ladevorgänge überholen sich) überschreibt nie einen bereits angezeigten
 * neueren Prüfstatus; sonst gilt der Stand der Antwort (Serverstand). Fahrzeuge, die der Server nicht mehr liefert, entfallen.
 */
export function mergeInspectionStatus(
  shown: readonly OperatorVehicle[] | undefined,
  incoming: readonly OperatorVehicle[],
): OperatorVehicle[] {
  if (!shown) return [...incoming];
  const known = new Map(shown.map((v) => [v.vehicle_id, v]));
  return incoming.map((v) => {
    const prev = known.get(v.vehicle_id);
    return prev && prev.inspection_status_revision > v.inspection_status_revision
      ? {
          ...v,
          inspection_status: prev.inspection_status,
          inspection_status_revision: prev.inspection_status_revision,
        }
      : v;
  });
}

/** Zeitgesteuertes Nachladen im Aktualisierungsintervall (OP-05); endet mit dem Aushängen der Ansicht. */
export function useInspectionRefresh(reload: () => void, intervalMs = INSPECTION_REFRESH_MS): void {
  const fn = useRef(reload);
  useEffect(() => {
    fn.current = reload;
  });
  useEffect(() => {
    const id = setInterval(() => fn.current(), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
}

/** Sagt Änderungen des Prüfstatus in der Live-Region an (zurückhaltend: nur echte Wechsel, nicht den ersten Ladevorgang). */
export function useAnnounceInspectionChanges(list: readonly OperatorVehicle[] | undefined): void {
  const announce = useAnnounce();
  const before = useRef<Map<string, InspectionStatus> | undefined>(undefined);
  useEffect(() => {
    if (!list) return;
    const now = new Map(list.map((v) => [v.vehicle_id, v.inspection_status]));
    const prev = before.current;
    before.current = now;
    if (!prev) return;
    for (const v of list) {
      const was = prev.get(v.vehicle_id);
      if (was !== undefined && was !== v.inspection_status)
        announce(`Prüfstatus ${v.vehicle_label}: ${INSPECTION_STATUS[v.inspection_status].label}.`);
    }
  }, [list, announce]);
}
