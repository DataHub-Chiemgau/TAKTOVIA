import type { AvailabilityRestriction, Driver, RosterConflict, Shift } from '@taktovia/api-client';
import { ScrollRegion, StatusBadge } from '@taktovia/design-system';
import { useState } from 'react';
import type { DragEvent, ReactNode } from 'react';
import {
  KONFLIKT_KURZ,
  KONFLIKT_TEXT,
  OFFEN_KEY,
  abwesendAm,
  fahrzeitText,
  istOffen,
  mondayOf,
  tagKurz,
  tagLang,
  zeilen,
} from './model';
import type { Ansicht, RosterBundle, Zeile, Zeitraum } from './model';
import './dienstplan.css';

/** Meldung direkt an der Schicht (Konfliktursache, Speicherfehler); Ton nie nur Farbe. */
export interface SchichtMeldung {
  tone: 'error' | 'ok';
  text: string;
}

/** Bearbeitungsaktionen von UI-12 (WP-M4-19); ohne Aktionen ist das Raster eine reine Lesesicht. */
export interface SchichtAktionen {
  anlegen: (zeile: Zeile, day: string) => void;
  kopieren: (shift: Shift) => void;
  verschieben: (shift: Shift) => void;
  /** Drag-and-drop: Schicht wurde auf Zelle (Zeile, Tag) abgelegt. */
  ablegen: (shiftId: string, zeile: Zeile, day: string) => void;
}

export interface RasterProps {
  ansicht: Ansicht;
  zeitraum: Zeitraum;
  bundles: RosterBundle[];
  drivers: readonly Driver[];
  absences: readonly AvailabilityRestriction[];
  fahrzeuge: ReadonlyMap<string, string>;
  konflikte: ReadonlyMap<string, RosterConflict[]>;
  meldungen: ReadonlyMap<string, SchichtMeldung>;
  /** Schichten, die gerade gespeichert werden. */
  speichernd: ReadonlySet<string>;
  aktionen?: SchichtAktionen | undefined;
}

export const isoWoche = (monday: string): number => {
  const d = new Date(`${monday}T00:00:00Z`);
  const thursday = new Date(d.getTime() + 3 * 86_400_000);
  const jan1 = new Date(Date.UTC(thursday.getUTCFullYear(), 0, 1));
  return Math.floor((thursday.getTime() - jan1.getTime()) / 86_400_000 / 7) + 1;
};

const SIDE_LABEL = { DRAFT: 'Entwurf', PUBLISHED: 'Veröffentlicht' } as const;

export function bundleAm(bundles: readonly RosterBundle[], day: string): RosterBundle | undefined {
  const monday = mondayOf(day);
  return bundles.find((b) => b.roster.week_start === monday);
}

function schichtenIn(bundles: readonly RosterBundle[]): Shift[] {
  return bundles.flatMap((b) => b.shifts);
}

/** Kopfzeile je Woche: Revisionsstatus prominent und in Text (Entwurf und veröffentlichte Revision getrennt, Kap. 06 §6.1). */
function wochenKopf(zeitraum: Zeitraum, bundles: readonly RosterBundle[]): ReactNode {
  const cells: ReactNode[] = [];
  for (const monday of zeitraum.mondays) {
    const span = zeitraum.days.filter((d) => mondayOf(d) === monday).length;
    const b = bundles.find((x) => x.roster.week_start === monday);
    const state = b?.side?.state;
    cells.push(
      <th
        key={monday}
        scope="colgroup"
        colSpan={span}
        className="dp-woche"
        data-state={state ?? 'none'}
      >
        KW {isoWoche(monday)}{' '}
        {state ? (
          <StatusBadge tone={state === 'DRAFT' ? 'warning' : 'ok'}>
            {SIDE_LABEL[state]}, Revision {b?.side?.revision}
          </StatusBadge>
        ) : (
          <StatusBadge tone="neutral">kein Dienstplan</StatusBadge>
        )}
      </th>,
    );
  }
  return cells;
}

function SchichtEintrag({
  shift,
  person,
  p,
  editable,
}: {
  shift: Shift;
  person: string;
  p: RasterProps;
  editable: boolean;
}) {
  const konflikte = p.konflikte.get(shift.id) ?? [];
  const meldung = p.meldungen.get(shift.id);
  const busy = p.speichernd.has(shift.id);
  const fahrzeug = shift.vehicle_id
    ? (p.fahrzeuge.get(shift.vehicle_id) ?? `Fahrzeug ${shift.vehicle_id.slice(0, 8)}`)
    : null;
  const kontext = `${fahrzeitText(shift)} am ${tagKurz(shift.date)}, ${person}`;
  const a = editable ? p.aktionen : undefined;
  const onDragStart = (e: DragEvent) => {
    e.dataTransfer.setData('text/plain', shift.id);
    e.dataTransfer.effectAllowed = 'move';
  };
  return (
    <li
      className="dp-schicht"
      data-offen={istOffen(shift)}
      data-konflikt={konflikte.length > 0}
      data-shift-id={shift.id}
      draggable={a ? true : undefined}
      onDragStart={a ? onDragStart : undefined}
      aria-busy={busy || undefined}
    >
      <span className="dp-zeit">{fahrzeitText(shift)}</span>{' '}
      <span className="dp-funktion">{shift.function}</span>
      {fahrzeug ? <span className="dp-fahrzeug"> · {fahrzeug}</span> : null}
      {istOffen(shift) ? (
        <>
          {' '}
          <StatusBadge tone="warning">Offen</StatusBadge>
        </>
      ) : null}
      {busy ? <span className="dp-speichern"> wird gespeichert …</span> : null}
      {konflikte.map((c) => (
        <div key={`${c.code}-${c.person_ref}`} className="dp-konflikt">
          <StatusBadge tone="error">Konflikt: {KONFLIKT_KURZ[c.code] ?? c.code}</StatusBadge>
          <span className="dp-konflikt-text" role="note">
            {' '}
            {KONFLIKT_TEXT[c.code] ?? c.cause}
            {c.code === 'ROSTER_QUALIFICATION_MISSING' && c.missing_qualification
              ? ` Fehlend: ${c.missing_qualification}.`
              : ''}
          </span>
        </div>
      ))}
      {meldung ? (
        <div className="dp-meldung" role={meldung.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={meldung.tone}>
            {meldung.tone === 'error' ? 'Fehler' : 'Gespeichert'}
          </StatusBadge>{' '}
          {meldung.text}
        </div>
      ) : null}
      {a ? (
        <span className="dp-aktionen">
          <button
            type="button"
            onClick={() => a.verschieben(shift)}
            aria-label={`Verschieben/Zuweisen, Schicht ${kontext}`}
          >
            Verschieben/Zuweisen
          </button>
          <button
            type="button"
            onClick={() => a.kopieren(shift)}
            aria-label={`Kopieren, Schicht ${kontext}`}
          >
            Kopieren
          </button>
        </span>
      ) : null}
    </li>
  );
}

function Raster(p: RasterProps) {
  const alle = schichtenIn(p.bundles);
  const rows = zeilen(p.drivers, alle);
  const [ueber, setUeber] = useState<string | null>(null);
  const days = p.zeitraum.days;
  return (
    <div className="dp-raster" data-ansicht={p.ansicht}>
      <ScrollRegion label="Dienstplanraster">
        <table className="tk-table dp-tabelle">
          <caption>
            Dienstplan {p.ansicht === 'tag' ? 'Tag' : p.ansicht === 'woche' ? 'Woche' : 'Monat'}:
            Mitarbeiter als Zeilen, Tage als Spalten
          </caption>
          <thead>
            <tr>
              <th scope="col" rowSpan={2} className="dp-name">
                Mitarbeiter
              </th>
              {wochenKopf(p.zeitraum, p.bundles)}
            </tr>
            <tr>
              {days.map((d) => (
                <th key={d} scope="col" className="dp-tag" title={tagLang(d)}>
                  {tagKurz(d)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((z) => {
              const offenZeile = z.key === OFFEN_KEY;
              const offeneAnzahl = alle.filter(istOffen).length;
              return (
                <tr key={z.key} data-offen-zeile={offenZeile || undefined}>
                  <th scope="row" className="dp-name">
                    {z.label}
                    {offenZeile ? (
                      <>
                        {' '}
                        <StatusBadge tone={offeneAnzahl > 0 ? 'warning' : 'ok'}>
                          {offeneAnzahl} offen
                        </StatusBadge>
                      </>
                    ) : null}
                  </th>
                  {days.map((d) => {
                    const bundle = bundleAm(p.bundles, d);
                    const editable = bundle?.side?.state === 'DRAFT' && p.aktionen !== undefined;
                    const eintraege = (bundle?.shifts ?? []).filter(
                      (s) =>
                        s.date === d && (offenZeile ? istOffen(s) : s.driver_ref === z.driverId),
                    );
                    const abwesend =
                      !offenZeile && z.driverId !== null && abwesendAm(p.absences, z.driverId, d);
                    const zellKey = `${z.key}|${d}`;
                    return (
                      <td
                        key={d}
                        className="dp-zelle"
                        data-ueber={ueber === zellKey || undefined}
                        data-abwesend={abwesend || undefined}
                        onDragOver={
                          editable
                            ? (e) => {
                                e.preventDefault();
                                e.dataTransfer.dropEffect = 'move';
                                setUeber(zellKey);
                              }
                            : undefined
                        }
                        onDragLeave={editable ? () => setUeber(null) : undefined}
                        onDrop={
                          editable
                            ? (e) => {
                                e.preventDefault();
                                setUeber(null);
                                const id = e.dataTransfer.getData('text/plain');
                                if (id) p.aktionen?.ablegen(id, z, d);
                              }
                            : undefined
                        }
                      >
                        {abwesend ? (
                          <StatusBadge tone="neutral">nicht verfügbar</StatusBadge>
                        ) : null}
                        {eintraege.length > 0 ? (
                          <ul className="dp-liste">
                            {eintraege.map((s) => (
                              <SchichtEintrag
                                key={s.id}
                                shift={s}
                                person={z.label}
                                p={p}
                                editable={editable}
                              />
                            ))}
                          </ul>
                        ) : null}
                        {editable && p.aktionen ? (
                          <button
                            type="button"
                            className="dp-neu"
                            onClick={() => p.aktionen?.anlegen(z, d)}
                            aria-label={`Schicht anlegen für ${z.label} am ${tagKurz(d)}`}
                          >
                            +
                          </button>
                        ) : null}
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </ScrollRegion>
    </div>
  );
}

/** Tabellenalternative: lineare Liste der Schichten, vollständig per Tastatur und Screenreader nutzbar (Kap. 06 §6.1). */
function Tabellenalternative(p: RasterProps) {
  const label = new Map(
    zeilen(p.drivers, schichtenIn(p.bundles)).map((z) => [z.driverId, z.label] as const),
  );
  const eintraege = p.bundles
    .flatMap((b) => b.shifts.map((s) => ({ s, b })))
    .filter(({ s }) => p.zeitraum.days.includes(s.date))
    .sort(
      (a, b) => a.s.starts_local.localeCompare(b.s.starts_local) || a.s.id.localeCompare(b.s.id),
    );
  return (
    <ScrollRegion label="Schichtliste">
      <table className="tk-table dp-liste-tabelle">
        <caption>Schichtliste (Tabellenalternative zum Raster)</caption>
        <thead>
          <tr>
            <th scope="col">Tag</th>
            <th scope="col">Zeit</th>
            <th scope="col">Mitarbeiter</th>
            <th scope="col">Fahrzeug</th>
            <th scope="col">Funktion</th>
            <th scope="col">Status</th>
            <th scope="col">Revision</th>
            <th scope="col">Aktionen</th>
          </tr>
        </thead>
        <tbody>
          {eintraege.map(({ s, b }) => {
            const person = s.driver_ref
              ? (label.get(s.driver_ref) ?? s.driver_ref.slice(0, 8))
              : 'offen';
            const konflikte = p.konflikte.get(s.id) ?? [];
            const editable = b.side?.state === 'DRAFT' && p.aktionen !== undefined;
            const kontext = `${fahrzeitText(s)} am ${tagKurz(s.date)}, ${person}`;
            return (
              <tr key={s.id}>
                <th scope="row">{tagKurz(s.date)}</th>
                <td>{fahrzeitText(s)}</td>
                <td>{person}</td>
                <td>
                  {s.vehicle_id ? (p.fahrzeuge.get(s.vehicle_id) ?? s.vehicle_id.slice(0, 8)) : '–'}
                </td>
                <td>{s.function}</td>
                <td>
                  {istOffen(s) ? (
                    <StatusBadge tone="warning">Offen</StatusBadge>
                  ) : (
                    <StatusBadge tone="ok">Besetzt</StatusBadge>
                  )}{' '}
                  {konflikte.map((c) => (
                    <StatusBadge key={`${c.code}-${c.person_ref}`} tone="error">
                      Konflikt: {KONFLIKT_KURZ[c.code] ?? c.code}
                    </StatusBadge>
                  ))}
                </td>
                <td>{b.side ? `${SIDE_LABEL[b.side.state]}, Revision ${b.side.revision}` : '–'}</td>
                <td>
                  {editable && p.aktionen ? (
                    <>
                      <button
                        type="button"
                        onClick={() => p.aktionen?.verschieben(s)}
                        aria-label={`Verschieben/Zuweisen, Schicht ${kontext}`}
                      >
                        Verschieben/Zuweisen
                      </button>{' '}
                      <button
                        type="button"
                        onClick={() => p.aktionen?.kopieren(s)}
                        aria-label={`Kopieren, Schicht ${kontext}`}
                      >
                        Kopieren
                      </button>
                    </>
                  ) : (
                    '–'
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/**
 * Raster und Tabellenalternative von UI-12 (LH-6.23-SCH-001/-003). Mitarbeiter sind Zeilen, Schichten Einträge der Tageszellen; offene Dienste stehen in einer eigenen
 * Zeile mit Zähler und sind in Text („Offen“) und Symbol markiert, nie nur durch Farbe. Abwesenheit erscheint als „nicht verfügbar“. Mit Aktionen ist das Raster
 * bearbeitbar: Zellen sind Ablageziele für Drag-and-drop, jede Aktion hat eine Tastaturalternative (Schaltflächen je Schicht und Zelle).
 */
export function DienstplanRaster(p: RasterProps) {
  const [liste, setListe] = useState(false);
  return (
    <section aria-label="Dienstplan">
      <p>
        <button type="button" aria-pressed={liste} onClick={() => setListe((v) => !v)}>
          Tabellenalternative anzeigen
        </button>
      </p>
      {liste ? <Tabellenalternative {...p} /> : <Raster {...p} />}
    </section>
  );
}
