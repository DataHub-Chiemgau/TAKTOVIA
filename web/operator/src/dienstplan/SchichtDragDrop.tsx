import { ApiError, useApi } from '@taktovia/api-client';
import type {
  AvailabilityRestriction,
  Driver,
  QualificationRevision,
  RosterConflict,
  Shift,
  ShiftWrite,
} from '@taktovia/api-client';
import { ConflictDialog, DateTimeField, StatusBadge } from '@taktovia/design-system';
import type { DateTimeValue } from '@taktovia/design-system';
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent, ReactNode } from 'react';
import { bundleAm, isoWoche } from './DienstplanRaster';
import type { SchichtAktionen, SchichtMeldung } from './DienstplanRaster';
import { DEFAULT_ZONE } from '../ScreenContext';
import {
  abwesendAm,
  addDays,
  eignung,
  fahrzeitText,
  konfliktText,
  tagKurz,
  uhrzeit,
  verschoben,
} from './model';
import type { RosterBundle, Zeile } from './model';

/**
 * Bearbeitung in UI-12 (WP-M4-19, LH-6.23-SCH-003): Anlegen per Klick, Kopieren, Verschieben/Zuweisen per Drag-and-drop oder per Tastaturdialog, Veröffentlichen.
 * Interaktionsvertrag: jede Drag-and-drop-Aktion hat eine gleichwertige Tastaturalternative (Schaltflächen „Verschieben/Zuweisen“, „Kopieren“, „+“ je Zelle); Speichern läuft
 * asynchron ohne Seitenwechsel mit Meldung über eine Live-Region und Konfliktmeldung direkt an der Schicht; jede Änderung trägt `If-Match` (412 öffnet den Konfliktdialog
 * ohne stilles Überschreiben). Die Eignung („geeignete Personen“) ist ein Angebot der Oberfläche; der Server bleibt die Instanz der Konfliktprüfung.
 */

/** Fehlertext je Statuscode und Problemcode (Kap. 06 §6.9). */
export function erklaereFehler(e: unknown, fallback: string): string {
  if (!(e instanceof ApiError)) return fallback;
  const code = e.problem?.code;
  if (code === 'LOCAL_TIME_NONEXISTENT')
    return 'Diese Ortszeit existiert wegen der Zeitumstellung nicht. Bitte eine andere Zeit wählen.';
  if (code === 'LOCAL_TIME_AMBIGUOUS')
    return 'Diese Ortszeit kommt wegen der Zeitumstellung zweimal vor. Bitte eine eindeutige Zeit wählen.';
  switch (e.status) {
    case 0:
      return 'Keine Verbindung zum Server. Die Änderung wurde nicht gespeichert.';
    case 403:
      return 'Ihnen fehlt das Recht, den Dienstplan zu ändern.';
    case 404:
      return 'Schicht, Person, Depot oder Dienstplan wurde nicht gefunden.';
    case 409:
      return 'Die Schicht ist nicht mehr im Entwurf änderbar. Bitte den Stand neu laden.';
    case 422:
      return 'Die Eingabe ist ungültig (Zeiten, Betriebstag oder Pausenplan). Bitte prüfen.';
    case 428:
      return 'Der Stand der Schicht fehlt. Bitte neu laden.';
    case 429:
      return 'Zu viele Anfragen. Bitte kurz warten und erneut versuchen.';
    default:
      return fallback;
  }
}

interface DialogValues {
  driverId: string | null;
  day: string;
  start: string;
  end: string;
  function: string;
  qualification: string;
  need: number;
  vehicleId: string | null;
  operatorId: string;
}

type DialogState =
  | { mode: 'neu'; day: string; driverId: string | null }
  | { mode: 'kopie'; shift: Shift }
  | { mode: 'verschieben'; shift: Shift };

const TITEL = {
  neu: 'Schicht anlegen',
  kopie: 'Schicht kopieren',
  verschieben: 'Schicht verschieben oder zuweisen',
} as const;

function SchichtDialog({
  state,
  drivers,
  absences,
  quals,
  fahrzeuge,
  operatorId,
  fehler,
  onSubmit,
  onCancel,
}: {
  state: DialogState;
  drivers: readonly Driver[];
  absences: readonly AvailabilityRestriction[];
  quals: ReadonlyMap<string, readonly QualificationRevision[]>;
  fahrzeuge: ReadonlyMap<string, string>;
  operatorId: string;
  fehler: string | undefined;
  onSubmit: (v: DialogValues) => void;
  onCancel: () => void;
}) {
  const base = 'shift' in state ? state.shift : undefined;
  const [v, setV] = useState<DialogValues>({
    driverId: base?.driver_ref ?? ('driverId' in state ? state.driverId : null),
    day: base ? base.date : 'day' in state ? state.day : '',
    start: base ? uhrzeit(base.starts_local) : '06:00',
    end: base ? uhrzeit(base.ends_local) : '14:00',
    function: base?.function ?? 'DRIVER',
    qualification: base?.required_qualification ?? '',
    need: base?.staffing_need ?? 1,
    vehicleId: base?.vehicle_id ?? null,
    operatorId: base?.operator_id ?? operatorId,
  });
  const ref = useRef<HTMLDivElement>(null);
  const id = useId();
  const verschieben = state.mode === 'verschieben';
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>('select, input')?.focus();
    return () => previous?.focus?.();
  }, []);

  const set = <K extends keyof DialogValues>(k: K, value: DialogValues[K]) =>
    setV((o) => ({ ...o, [k]: value }));
  const required = verschieben
    ? (base?.required_qualification ?? null)
    : v.qualification.trim() || null;
  const aktive = drivers.filter((d) => d.status === 'ACTIVE' || d.id === v.driverId);
  const bewertet = aktive
    .map((d) => ({
      d,
      e: eignung(
        { required_qualification: required, date: v.day },
        quals.get(d.id),
        abwesendAm(absences, d.id, v.day),
      ),
    }))
    .sort((a, b) => (a.d.display_name ?? a.d.id).localeCompare(b.d.display_name ?? b.d.id, 'de'));
  const geeignet = bewertet.filter((x) => x.e.geeignet);
  const ungeeignet = bewertet.filter((x) => !x.e.geeignet);
  const gewaehlt = bewertet.find((x) => x.d.id === v.driverId);
  const name = (d: Driver) => d.display_name ?? `Mitarbeiter ${d.id.slice(0, 8)}`;

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onCancel();
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit(v);
  };
  const ungueltig =
    !v.day ||
    (!verschieben &&
      (!v.start || !v.end || !v.function.trim() || v.need < 1 || !v.operatorId.trim()));

  return (
    <div className="tk-dialog-backdrop">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-t`}
        className="tk-dialog"
        onKeyDown={onKeyDown}
      >
        <h2 id={`${id}-t`}>{TITEL[state.mode]}</h2>
        {base ? (
          <p>
            Schicht {fahrzeitText(base)} am {tagKurz(base.date)}
          </p>
        ) : null}
        <form onSubmit={submit} noValidate>
          <div className="dp-dialog-feld">
            <label htmlFor={`${id}-p`}>Person</label>
            <select
              id={`${id}-p`}
              value={v.driverId ?? ''}
              onChange={(e) => set('driverId', e.target.value || null)}
            >
              <option value="">Offen (keine Person)</option>
              <optgroup label="Geeignete Personen">
                {geeignet.map(({ d }) => (
                  <option key={d.id} value={d.id}>
                    {name(d)}
                  </option>
                ))}
              </optgroup>
              <optgroup label="Nicht geeignet">
                {ungeeignet.map(({ d, e }) => (
                  <option key={d.id} value={d.id}>
                    {name(d)} (nicht geeignet: {e.grund})
                  </option>
                ))}
              </optgroup>
            </select>
          </div>
          {gewaehlt && !gewaehlt.e.geeignet ? (
            <p role="status">
              <StatusBadge tone="warning">Ungeeignet</StatusBadge> {name(gewaehlt.d)}:{' '}
              {gewaehlt.e.grund}. Der Server meldet die Zuordnung als Konflikt an der Schicht;
              veröffentlicht werden kann der Dienstplan erst nach Behebung.
            </p>
          ) : null}
          <div className="dp-dialog-feld">
            <label htmlFor={`${id}-d`}>Tag (Betriebstag)</label>
            <input
              id={`${id}-d`}
              type="date"
              value={v.day}
              onChange={(e) => set('day', e.target.value)}
            />
          </div>
          {verschieben ? null : (
            <>
              <div className="dp-dialog-feld">
                <label htmlFor={`${id}-s`}>Beginn (lokale Zeit)</label>
                <input
                  id={`${id}-s`}
                  type="time"
                  value={v.start}
                  onChange={(e) => set('start', e.target.value)}
                />
              </div>
              <div className="dp-dialog-feld">
                <label htmlFor={`${id}-e`}>
                  Ende (lokale Zeit, am Folgetag bei früherer Uhrzeit)
                </label>
                <input
                  id={`${id}-e`}
                  type="time"
                  value={v.end}
                  onChange={(e) => set('end', e.target.value)}
                />
              </div>
              <div className="dp-dialog-feld">
                <label htmlFor={`${id}-f`}>Funktion</label>
                <input
                  id={`${id}-f`}
                  value={v.function}
                  onChange={(e) => set('function', e.target.value)}
                />
              </div>
              <div className="dp-dialog-feld">
                <label htmlFor={`${id}-q`}>Erforderliche Qualifikation (optional)</label>
                <input
                  id={`${id}-q`}
                  value={v.qualification}
                  onChange={(e) => set('qualification', e.target.value)}
                />
              </div>
              <div className="dp-dialog-feld">
                <label htmlFor={`${id}-n`}>Personalbedarf</label>
                <input
                  id={`${id}-n`}
                  type="number"
                  min={1}
                  value={v.need}
                  onChange={(e) => set('need', Number(e.target.value))}
                />
              </div>
              {v.driverId && fahrzeuge.size > 0 ? (
                <div className="dp-dialog-feld">
                  <label htmlFor={`${id}-v`}>Fahrzeug (optional)</label>
                  <select
                    id={`${id}-v`}
                    value={v.vehicleId ?? ''}
                    onChange={(e) => set('vehicleId', e.target.value || null)}
                  >
                    <option value="">Kein Fahrzeug</option>
                    {[...fahrzeuge.entries()].map(([vid, label]) => (
                      <option key={vid} value={vid}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
              {base || operatorId ? null : (
                <div className="dp-dialog-feld">
                  <label htmlFor={`${id}-o`}>Betrieb (Kennung)</label>
                  <input
                    id={`${id}-o`}
                    value={v.operatorId}
                    onChange={(e) => set('operatorId', e.target.value)}
                  />
                </div>
              )}
            </>
          )}
          {fehler ? (
            <p role="alert">
              <StatusBadge tone="error">Fehler</StatusBadge> {fehler}
            </p>
          ) : null}
          <div className="tk-dialog-actions">
            <button type="submit" disabled={ungueltig}>
              Speichern
            </button>
            <button type="button" className="tk-button-secondary" onClick={onCancel}>
              Abbrechen
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export interface BearbeitungArgs {
  bundles: RosterBundle[];
  drivers: readonly Driver[];
  absences: readonly AvailabilityRestriction[];
  fahrzeuge: ReadonlyMap<string, string>;
  /** Lädt den Stand still neu (ohne Ladezustand, kein Seitenwechsel). */
  reload: () => Promise<void>;
  /** Übernimmt die gespeicherte Schicht sofort in die Anzeige. */
  patchShift: (s: Shift) => void;
  announce: (text: string) => void;
}

/** Hook mit der gesamten Bearbeitungslogik; liefert Aktionen für das Raster, Meldungen und die Dialoge. */
export function useSchichtBearbeitung(a: BearbeitungArgs) {
  const api = useApi();
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [dialogFehler, setDialogFehler] = useState<string | undefined>(undefined);
  const [meldungen, setMeldungen] = useState<ReadonlyMap<string, SchichtMeldung>>(new Map());
  const [speichernd, setSpeichernd] = useState<ReadonlySet<string>>(new Set());
  const [extra, setExtra] = useState<ReadonlyMap<string, RosterConflict[]>>(new Map());
  const [stale, setStale] = useState<{ beschreibung: string; pruefen: boolean } | null>(null);
  const [quals, setQuals] = useState<ReadonlyMap<string, readonly QualificationRevision[]>>(
    new Map(),
  );

  const alleSchichten = useMemo(() => a.bundles.flatMap((b) => b.shifts), [a.bundles]);
  const operatorId = alleSchichten[0]?.operator_id ?? '';
  const nameOf = (id: string | null) =>
    id
      ? (a.drivers.find((d) => d.id === id)?.display_name ?? `Mitarbeiter ${id.slice(0, 8)}`)
      : 'offen';

  // Qualifikationen der aktiven Fahrer zum Beginn der betroffenen Schicht (Angebot „geeignete Personen“).
  const dialogAt = dialog && 'shift' in dialog ? dialog.shift.starts_at : undefined;
  const dialogOffen = dialog !== null;
  useEffect(() => {
    if (!dialogOffen) return;
    let cancelled = false;
    void (async () => {
      const entries = await Promise.all(
        a.drivers
          .filter((d) => d.status === 'ACTIVE')
          .map(async (d) => {
            try {
              const page = await api.call('listDriverQualifications', {
                path: { driver_id: d.id },
                query: dialogAt ? { at: dialogAt } : {},
              });
              return [d.id, page.items] as const;
            } catch {
              return null;
            }
          }),
      );
      if (!cancelled)
        setQuals(new Map(entries.filter((x): x is NonNullable<typeof x> => x !== null)));
    })();
    return () => {
      cancelled = true;
    };
  }, [api, a.drivers, dialogOffen, dialogAt]);

  const setBusy = (id: string, on: boolean) =>
    setSpeichernd((old) => {
      const next = new Set(old);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  const setMeldung = (id: string, m: SchichtMeldung | undefined) =>
    setMeldungen((old) => {
      const next = new Map(old);
      if (m) next.set(id, m);
      else next.delete(id);
      return next;
    });

  const speichereAenderung = useCallback(
    async (shift: Shift, body: ShiftWrite, beschreibung: string): Promise<void> => {
      setMeldung(shift.id, undefined);
      setBusy(shift.id, true);
      a.announce(`Schicht wird gespeichert: ${beschreibung}`);
      try {
        const updated = await api.call('updateRosterShift', {
          path: { shift_id: shift.id },
          body,
          revision: shift.revision,
        });
        a.patchShift(updated);
        setExtra((old) => new Map(old).set(updated.id, updated.conflicts ?? []));
        const n = updated.conflicts?.length ?? 0;
        const text =
          n > 0
            ? `Gespeichert mit ${n} Konflikt${n === 1 ? '' : 'en'}: ${beschreibung}`
            : `Gespeichert: ${beschreibung}`;
        setMeldung(updated.id, { tone: 'ok', text });
        a.announce(text);
        await a.reload().catch(() => undefined);
      } catch (e) {
        if (e instanceof ApiError && e.status === 412) {
          setStale({ beschreibung, pruefen: false });
          a.announce(
            'Die Schicht wurde zwischenzeitlich geändert. Ihre Änderung wurde nicht gespeichert.',
          );
        } else {
          const text = erklaereFehler(e, 'Die Schicht konnte nicht gespeichert werden.');
          setMeldung(shift.id, { tone: 'error', text });
          a.announce(text);
        }
      } finally {
        setBusy(shift.id, false);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [api, a.announce, a.patchShift, a.reload],
  );

  const verschiebe = (shift: Shift, patch: { driverId?: string | null; day?: string }) => {
    const ziel = patch.driverId === undefined ? shift.driver_ref : patch.driverId;
    const tag = patch.day ?? shift.date;
    if (ziel === shift.driver_ref && tag === shift.date) return;
    const beschreibung = `${fahrzeitText(shift)} am ${tagKurz(tag)} für ${nameOf(ziel)}`;
    void speichereAenderung(shift, verschoben(shift, patch), beschreibung);
  };

  const legeAn = async (state: DialogState, v: DialogValues): Promise<string | undefined> => {
    const bundle = bundleAm(a.bundles, v.day);
    if (!bundle || bundle.side?.state !== 'DRAFT')
      return 'Für diesen Tag gibt es keinen Entwurf. Bitte zuerst einen Entwurf anlegen.';
    const overnight = v.end <= v.start ? 1 : 0;
    const body: ShiftWrite = {
      roster_id: bundle.roster.id,
      starts_local: `${v.day}T${v.start}`,
      ends_local: `${addDays(v.day, overnight)}T${v.end}`,
      timezone:
        'shift' in state ? state.shift.timezone : (alleSchichten[0]?.timezone ?? DEFAULT_ZONE),
      operator_id: v.operatorId.trim(),
      depot_id: bundle.roster.depot_id,
      function: v.function.trim(),
      ...(v.qualification.trim() ? { required_qualification: v.qualification.trim() } : {}),
      staffing_need: v.need,
      driver_ref: v.driverId,
      vehicle_id: v.driverId ? v.vehicleId : null,
    };
    try {
      const created = await api.call('createRosterShift', { body });
      a.patchShift(created);
      setExtra((old) => new Map(old).set(created.id, created.conflicts ?? []));
      const text = `Schicht angelegt (Entwurf): ${fahrzeitText(created)} am ${tagKurz(created.date)} für ${nameOf(created.driver_ref)}`;
      setMeldung(created.id, { tone: 'ok', text });
      a.announce(text);
      await a.reload().catch(() => undefined);
      return undefined;
    } catch (e) {
      const text = erklaereFehler(e, 'Die Schicht konnte nicht angelegt werden.');
      a.announce(text);
      return text;
    }
  };

  const submit = async (v: DialogValues) => {
    if (!dialog) return;
    setDialogFehler(undefined);
    if (dialog.mode === 'verschieben') {
      const shift = dialog.shift;
      setDialog(null);
      verschiebe(shift, { driverId: v.driverId, day: v.day });
      return;
    }
    const fehler = await legeAn(dialog, v);
    if (fehler) setDialogFehler(fehler);
    else setDialog(null);
  };

  const aktionen: SchichtAktionen = {
    anlegen: (zeile: Zeile, day: string) => {
      setDialogFehler(undefined);
      setDialog({ mode: 'neu', day, driverId: zeile.driverId });
    },
    kopieren: (shift) => {
      setDialogFehler(undefined);
      setDialog({ mode: 'kopie', shift });
    },
    verschieben: (shift) => {
      setDialogFehler(undefined);
      setDialog({ mode: 'verschieben', shift });
    },
    ablegen: (shiftId, zeile, day) => {
      const shift = alleSchichten.find((s) => s.id === shiftId);
      if (shift) verschiebe(shift, { driverId: zeile.driverId, day });
    },
  };

  const ui: ReactNode = (
    <>
      {dialog ? (
        <SchichtDialog
          state={dialog}
          drivers={a.drivers}
          absences={a.absences}
          quals={quals}
          fahrzeuge={a.fahrzeuge}
          operatorId={operatorId}
          fehler={dialogFehler}
          onSubmit={(v) => void submit(v)}
          onCancel={() => setDialog(null)}
        />
      ) : null}
      <ConflictDialog
        open={stale !== null}
        onReload={() => {
          setStale(null);
          void a.reload();
        }}
        onReview={() => setStale((s) => (s ? { ...s, pruefen: true } : s))}
        onCancel={() => setStale(null)}
      />
      {stale?.pruefen ? (
        <p role="status" className="dp-pruefen">
          Ihre nicht gespeicherte Änderung: {stale.beschreibung}. Laden Sie den aktuellen Stand und
          führen Sie die Änderung bei Bedarf erneut aus.
        </p>
      ) : null}
    </>
  );

  return { aktionen, meldungen, speichernd, extra, ui };
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Veröffentlichen
// ---------------------------------------------------------------------------------------------------------------------------------

/**
 * „Entwurf veröffentlichen“ je Woche: zeigt blockierende Konflikte (Veröffentlichung gesperrt, solange welche bestehen) und sammelt Begründung, Wirksamkeit und die Kennung
 * des Wirkungsberichts (Vertrag `PublishRequest`). Die Veröffentlichung ist eine neue Revision; 412 öffnet den Konfliktdialog, ohne zu überschreiben.
 */
export function Veroeffentlichen({
  bundles,
  onDone,
  announce,
}: {
  bundles: RosterBundle[];
  onDone: () => Promise<void>;
  announce: (t: string) => void;
}) {
  const api = useApi();
  const drafts = bundles.filter((b) => b.side?.state === 'DRAFT');
  const [offen, setOffen] = useState<RosterBundle | null>(null);
  const [reason, setReason] = useState('');
  const [wirksam, setWirksam] = useState<DateTimeValue & { instant: string | null }>({
    local: '',
    offset: null,
    instant: null,
  });
  const [bericht, setBericht] = useState('');
  const [fehler, setFehler] = useState<string | undefined>(undefined);
  const [stale, setStale] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | undefined>(undefined);
  const id = useId();
  if (drafts.length === 0) return null;

  const publish = async () => {
    if (!offen || !wirksam.instant) return;
    setBusy(true);
    setFehler(undefined);
    try {
      const res = await api.call('publishRoster', {
        path: { roster_id: offen.roster.id },
        body: {
          effective_from: wirksam.instant,
          reason: reason.trim(),
          impact_report_id: bericht.trim(),
        },
        revision: offen.roster.revision,
      });
      const text = `Dienstplan KW ${isoWoche(offen.roster.week_start)} veröffentlicht (Revision ${res.revision}).`;
      setDone(text);
      announce(text);
      setOffen(null);
      await onDone().catch(() => undefined);
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) setStale(true);
      else {
        const t =
          e instanceof ApiError && (e.status === 409 || e.status === 422)
            ? 'Die Veröffentlichung wurde abgelehnt: blockierende Konflikte, fehlender Wirkungsbericht oder ungültige Angaben. Bitte Konflikte prüfen.'
            : erklaereFehler(e, 'Der Dienstplan konnte nicht veröffentlicht werden.');
        setFehler(t);
        announce(t);
      }
    } finally {
      setBusy(false);
    }
  };

  const ok = reason.trim().length >= 3 && wirksam.instant !== null && bericht.trim().length > 0;
  return (
    <section aria-label="Entwurf veröffentlichen" className="dp-veroeffentlichen">
      <h2>Entwurf veröffentlichen</h2>
      {done ? <p role="status">{done}</p> : null}
      <ul>
        {drafts.map((b) => {
          const konflikte = b.roster.conflicts ?? [];
          return (
            <li key={b.roster.id}>
              <strong>
                KW {isoWoche(b.roster.week_start)} · Entwurf, Revision {b.side?.revision}
              </strong>{' '}
              {konflikte.length > 0 ? (
                <StatusBadge tone="error">{konflikte.length} blockierende Konflikte</StatusBadge>
              ) : (
                <StatusBadge tone="ok">keine blockierenden Konflikte</StatusBadge>
              )}
              {konflikte.length > 0 ? (
                <ul aria-label={`Blockierende Konflikte KW ${isoWoche(b.roster.week_start)}`}>
                  {konflikte.map((c) => (
                    <li key={`${c.code}-${c.person_ref}-${c.shift_ids.join()}`}>
                      {konfliktText(c)}
                    </li>
                  ))}
                </ul>
              ) : null}
              <p>
                <button type="button" disabled={konflikte.length > 0} onClick={() => setOffen(b)}>
                  Entwurf veröffentlichen
                </button>
                {konflikte.length > 0
                  ? ' Veröffentlichen ist gesperrt, bis die Konflikte behoben sind.'
                  : null}
              </p>
            </li>
          );
        })}
      </ul>
      {offen ? (
        <div className="tk-dialog-backdrop">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby={`${id}-t`}
            className="tk-dialog"
            onKeyDown={(e) => {
              if (e.key === 'Escape') setOffen(null);
            }}
          >
            <h2 id={`${id}-t`}>Entwurf KW {isoWoche(offen.roster.week_start)} veröffentlichen</h2>
            <div className="dp-dialog-feld">
              <label htmlFor={`${id}-r`}>Begründung</label>
              <input id={`${id}-r`} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <DateTimeField
              label="Wirksam ab"
              zone={DEFAULT_ZONE}
              value={wirksam}
              onChange={setWirksam}
            />
            <div className="dp-dialog-feld">
              <label htmlFor={`${id}-i`}>Wirkungsbericht (Kennung)</label>
              <input id={`${id}-i`} value={bericht} onChange={(e) => setBericht(e.target.value)} />
            </div>
            {fehler ? (
              <p role="alert">
                <StatusBadge tone="error">Fehler</StatusBadge> {fehler}
              </p>
            ) : null}
            <div className="tk-dialog-actions">
              <button type="button" disabled={!ok || busy} onClick={() => void publish()}>
                Veröffentlichen
              </button>
              <button type="button" className="tk-button-secondary" onClick={() => setOffen(null)}>
                Abbrechen
              </button>
            </div>
          </div>
        </div>
      ) : null}
      <ConflictDialog
        open={stale}
        onReload={() => {
          setStale(false);
          setOffen(null);
          void onDone();
        }}
        onReview={() => setStale(false)}
        onCancel={() => setStale(false)}
      />
    </section>
  );
}
