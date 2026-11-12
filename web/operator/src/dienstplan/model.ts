import type {
  AvailabilityRestriction,
  Driver,
  QualificationRevision,
  Roster,
  RosterConflict,
  RosterSide,
  Shift,
  ShiftWrite,
} from '@taktovia/api-client';

/**
 * Reines Modell von UI-12 (WP-M4-18/-19, LH-6.23-SCH-001/-003): Zeiträume, Zeilen, Zellen, Eignung und Schichtänderungen. Keine Rechtequelle:
 * die Eignung ist ein Angebot der Oberfläche, der Server (Konfliktprüfung WP-M4-07) bleibt maßgeblich.
 */

export type Ansicht = 'tag' | 'woche' | 'monat';
export type Revisionssicht = 'planner' | 'published';

const DAY_MS = 86_400_000;
const parse = (iso: string): number => Date.parse(`${iso}T00:00:00Z`);
const fmt = (ms: number): string => new Date(ms).toISOString().slice(0, 10);

export const addDays = (iso: string, n: number): string => fmt(parse(iso) + n * DAY_MS);
export const daysBetween = (a: string, b: string): number =>
  Math.round((parse(b) - parse(a)) / DAY_MS);
/** Montag der Kalenderwoche (ISO 8601) zu einem lokalen Datum. */
export const mondayOf = (iso: string): string => {
  const dow = (new Date(parse(iso)).getUTCDay() + 6) % 7; // Montag = 0
  return addDays(iso, -dow);
};

export interface Zeitraum {
  /** Alle angezeigten Tage (lokale Daten), aufsteigend. */
  days: string[];
  /** Montage der berührten Kalenderwochen, aufsteigend (Schlüssel der Dienstpläne). */
  mondays: string[];
}

/** Zeitraum zur Ansicht: Tag, Woche (1 oder 2 Wochen ab Montag) oder Kalendermonat. */
export function zeitraum(ansicht: Ansicht, anker: string, wochen: 1 | 2 = 1): Zeitraum {
  let days: string[];
  if (ansicht === 'tag') days = [anker];
  else if (ansicht === 'woche') {
    const start = mondayOf(anker);
    days = Array.from({ length: 7 * wochen }, (_, i) => addDays(start, i));
  } else {
    const first = `${anker.slice(0, 7)}-01`;
    const next = new Date(Date.UTC(Number(anker.slice(0, 4)), Number(anker.slice(5, 7)), 1));
    const count = daysBetween(first, fmt(next.getTime()));
    days = Array.from({ length: count }, (_, i) => addDays(first, i));
  }
  const mondays = [...new Set(days.map(mondayOf))];
  return { days, mondays };
}

/** Anker nach „vor“/„zurück“: Tag +-1, Woche +-7 Tage je angezeigte Wochen, Monat +-1 Monat. */
export function verschiebeAnker(
  ansicht: Ansicht,
  anker: string,
  richtung: 1 | -1,
  wochen: 1 | 2,
): string {
  if (ansicht === 'tag') return addDays(anker, richtung);
  if (ansicht === 'woche') return addDays(anker, 7 * wochen * richtung);
  const d = new Date(
    Date.UTC(Number(anker.slice(0, 4)), Number(anker.slice(5, 7)) - 1 + richtung, 1),
  );
  return fmt(d.getTime());
}

const WOCHENTAG = new Intl.DateTimeFormat('de-DE', { weekday: 'short', timeZone: 'UTC' });
const TAGMONAT = new Intl.DateTimeFormat('de-DE', {
  day: '2-digit',
  month: '2-digit',
  timeZone: 'UTC',
});
const LANG = new Intl.DateTimeFormat('de-DE', {
  weekday: 'long',
  day: '2-digit',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});
export const tagKurz = (iso: string): string =>
  `${WOCHENTAG.format(parse(iso))} ${TAGMONAT.format(parse(iso))}`;
export const tagLang = (iso: string): string => LANG.format(parse(iso));

export const uhrzeit = (local: string): string => local.slice(11, 16);
export const datum = (local: string): string => local.slice(0, 10);

export function fahrzeitText(s: Pick<Shift, 'starts_local' | 'ends_local'>): string {
  const overnight = datum(s.ends_local) !== datum(s.starts_local);
  return `${uhrzeit(s.starts_local)}–${uhrzeit(s.ends_local)}${overnight ? ' (+1 Tag)' : ''}`;
}

/** Fehlertext je Konfliktcode (Kap. 06 §6.9: Ursache benennen, nie nur Farbe). */
export const KONFLIKT_TEXT: Readonly<Record<RosterConflict['code'], string>> = {
  ROSTER_DOUBLE_BOOKING: 'Doppelbelegung: Die Person hat zur selben Zeit eine weitere Schicht.',
  ROSTER_QUALIFICATION_MISSING: 'Qualifikation fehlt oder ist zur Schichtzeit nicht gültig.',
  ROSTER_ABSENCE_OVERLAP: 'Abwesenheit: Die Person ist zur Schichtzeit nicht verfügbar.',
};
export const KONFLIKT_KURZ: Readonly<Record<RosterConflict['code'], string>> = {
  ROSTER_DOUBLE_BOOKING: 'Doppelbelegung',
  ROSTER_QUALIFICATION_MISSING: 'Qualifikation fehlt',
  ROSTER_ABSENCE_OVERLAP: 'Abwesenheit',
};

export function konfliktText(c: RosterConflict): string {
  const base = KONFLIKT_TEXT[c.code] ?? c.cause;
  return c.code === 'ROSTER_QUALIFICATION_MISSING' && c.missing_qualification
    ? `${base} Fehlend: ${c.missing_qualification}.`
    : base;
}

/** Ein Dienstplan (Woche eines Depots) mit der angezeigten Seite und seinen Schichten. */
export interface RosterBundle {
  roster: Roster;
  /** Angezeigte Revision: Entwurf (Arbeitsstand) oder veröffentlicht; `null`, wenn die gewählte Sicht keine Revision hat. */
  side: RosterSide | null;
  shifts: Shift[];
}

export const sideOf = (roster: Roster, sicht: Revisionssicht): RosterSide | null =>
  sicht === 'published' ? roster.published : (roster.draft ?? roster.published);

export interface Zeile {
  key: string;
  /** Fahrer-ID; `null` für die Zeile der offenen Dienste. */
  driverId: string | null;
  label: string;
}
export const OFFEN_KEY = 'offen';

/** Konflikte je Schicht aus Dienstplan-Prüfung und Antwort der Schicht (ohne Dubletten). */
export function konflikteJeSchicht(
  bundles: RosterBundle[],
  extra: ReadonlyMap<string, RosterConflict[]>,
): Map<string, RosterConflict[]> {
  const out = new Map<string, RosterConflict[]>();
  const add = (id: string, c: RosterConflict) => {
    const list = out.get(id) ?? [];
    if (!list.some((x) => x.code === c.code && x.person_ref === c.person_ref)) list.push(c);
    out.set(id, list);
  };
  for (const b of bundles) {
    for (const c of b.roster.conflicts ?? []) for (const id of c.shift_ids) add(id, c);
    for (const s of b.shifts) for (const c of s.conflicts ?? []) add(s.id, c);
  }
  for (const [id, cs] of extra) for (const c of cs) add(id, c);
  return out;
}

/** Zeilen: eine je Mitarbeiter (alle aktiven Fahrer und jeder in Schichten vorkommende), danach die Zeile der offenen Dienste. */
export function zeilen(drivers: readonly Driver[], shifts: readonly Shift[]): Zeile[] {
  const named = new Map<string, string>();
  for (const d of drivers)
    if (d.status === 'ACTIVE') named.set(d.id, d.display_name ?? `Mitarbeiter ${d.id.slice(0, 8)}`);
  const byId = new Map(drivers.map((d) => [d.id, d] as const));
  for (const s of shifts) {
    if (s.driver_ref && !named.has(s.driver_ref))
      named.set(
        s.driver_ref,
        byId.get(s.driver_ref)?.display_name ?? `Mitarbeiter ${s.driver_ref.slice(0, 8)}`,
      );
  }
  const people = [...named.entries()]
    .sort((a, b) => a[1].localeCompare(b[1], 'de'))
    .map(([id, label]): Zeile => ({ key: id, driverId: id, label }));
  return [...people, { key: OFFEN_KEY, driverId: null, label: 'Offene Dienste' }];
}

export const istOffen = (s: Shift): boolean => s.driver_ref === null;

/** Anzahl offener Dienste im Zeitraum (Schichten ohne Person) und noch nicht gedeckter Personalbedarf. */
export function offeneZaehler(shifts: readonly Shift[]): { schichten: number; bedarf: number } {
  let schichten = 0;
  let bedarf = 0;
  for (const s of shifts) {
    const assigned = s.driver_ref ? 1 : 0;
    if (!s.driver_ref) schichten += 1;
    bedarf += Math.max(s.staffing_need - assigned, 0);
  }
  return { schichten, bedarf };
}

export function abwesendAm(
  absences: readonly AvailabilityRestriction[],
  person: string,
  day: string,
): boolean {
  return absences.some(
    (a) => a.person_ref === person && a.from.slice(0, 10) <= day && day <= a.to.slice(0, 10),
  );
}

export interface Eignung {
  geeignet: boolean;
  grund: string | null;
}

/** Eignung einer Person für eine Schicht: gültige, freigegebene Qualifikation (falls verlangt) und keine Abwesenheit am Betriebstag. */
export function eignung(
  shift: Pick<Shift, 'required_qualification' | 'date'>,
  quals: readonly QualificationRevision[] | undefined,
  absent: boolean,
): Eignung {
  if (absent) return { geeignet: false, grund: 'Abwesenheit am Betriebstag' };
  const need = shift.required_qualification;
  if (need) {
    if (quals === undefined) return { geeignet: false, grund: 'Qualifikationen nicht lesbar' };
    const ok = quals.some(
      (q) => q.qualification_code === need && q.status === 'APPROVED' && q.effective && q.valid_at,
    );
    if (!ok) return { geeignet: false, grund: `Qualifikation ${need} fehlt oder nicht gültig` };
  }
  return { geeignet: true, grund: null };
}

/** Schreibmodell zur Schicht mit geänderter Person und/oder geändertem Betriebstag (Uhrzeiten und Dauer bleiben; Übernachtschichten behalten ihren Tagesversatz). */
export function verschoben(
  s: Shift,
  patch: { driverId?: string | null; day?: string },
): ShiftWrite {
  const day = patch.day ?? s.date;
  const offset = daysBetween(datum(s.starts_local), datum(s.ends_local));
  const driver = patch.driverId === undefined ? s.driver_ref : patch.driverId;
  return {
    roster_id: s.roster_id,
    starts_local: `${day}T${s.starts_local.slice(11)}`,
    ends_local: `${addDays(day, offset)}T${s.ends_local.slice(11)}`,
    timezone: s.timezone,
    break_plan: s.break_plan,
    operator_id: s.operator_id,
    depot_id: s.depot_id,
    function: s.function,
    ...(s.required_qualification ? { required_qualification: s.required_qualification } : {}),
    staffing_need: s.staffing_need,
    driver_ref: driver,
    // „offene Schicht trägt kein Fahrzeug“ (SCH-008): ohne Person entfällt das Fahrzeug
    vehicle_id: driver ? s.vehicle_id : null,
  };
}
