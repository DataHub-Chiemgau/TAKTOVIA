/**
 * Zeit- und Zonenlogik für die Oberfläche (LH-6.1-SYS-006, WP-M1-18): Anzeige in der Zone des Dienstes mit Offset, lokaler
 * Betriebstag, Auflösung einer lokalen Eingabe bei Zeitumstellung. Nur `Intl` des Browsers, keine Zeitbibliothek.
 * Der Server prüft dieselben Regeln verbindlich (Feldfehler OFFSET_REQUIRED, LOCAL_TIME_NONEXISTENT, LOCAL_TIME_AMBIGUOUS).
 */

const FORMATTERS = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let f = FORMATTERS.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    FORMATTERS.set(zone, f);
  }
  return f;
}

/** IANA-Zone der Region oder UTC (keine Abkürzungen, keine festen Offsets); dieselbe Form wie `TimeZone` in api/common.yaml. */
export function isKnownZone(zone: string): boolean {
  if (!/^(UTC|[A-Z][A-Za-z_-]*(\/[A-Za-z0-9_+-]+)+)$/.test(zone) || zone.startsWith('Etc/'))
    return false;
  try {
    formatter(zone);
    return true;
  } catch {
    return false;
  }
}

interface Parts {
  y: number;
  mo: number;
  d: number;
  h: number;
  mi: number;
  s: number;
}

function partsAt(epochMs: number, zone: string): Parts {
  const p: Record<string, number> = {};
  for (const x of formatter(zone).formatToParts(new Date(epochMs)))
    if (x.type !== 'literal') p[x.type] = Number(x.value);
  return {
    y: p.year ?? 0,
    mo: p.month ?? 0,
    d: p.day ?? 0,
    h: p.hour ?? 0,
    mi: p.minute ?? 0,
    s: p.second ?? 0,
  };
}

const asUtc = (p: Parts) => Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s);

/** UTC-Offset der Zone zum Zeitpunkt in Minuten. */
export function offsetMinutesAt(epochMs: number, zone: string): number {
  return Math.round(
    (asUtc(partsAt(Math.floor(epochMs / 1000) * 1000, zone)) - Math.floor(epochMs / 1000) * 1000) /
      60000,
  );
}

/** `+02:00`. */
export function formatOffset(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const a = Math.abs(minutes);
  return `${sign}${String(Math.floor(a / 60)).padStart(2, '0')}:${String(a % 60).padStart(2, '0')}`;
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0');

/** RFC 3339 mit dem Offset der Zone, z. B. `2026-10-25T02:30:00+01:00`. */
export function toRfc3339(epochMs: number, zone: string): string {
  const p = partsAt(epochMs, zone);
  return `${pad(p.y, 4)}-${pad(p.mo)}-${pad(p.d)}T${pad(p.h)}:${pad(p.mi)}:${pad(p.s)}${formatOffset(offsetMinutesAt(epochMs, zone))}`;
}

export interface ResolvedInstant {
  epochMs: number;
  offset: string;
}

export type LocalResolution =
  | { kind: 'unique'; value: ResolvedInstant }
  | { kind: 'ambiguous'; options: ResolvedInstant[] }
  | { kind: 'gap' }
  | { kind: 'invalid' }
  | { kind: 'unknown-zone' };

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;

/**
 * Löst eine lokale Eingabe (`2026-10-25T02:30` oder mit Sekunden) in der Zone auf: genau ein Zeitpunkt, zwei Zeitpunkte
 * (Herbstumstellung, Offset muss gewählt werden), keiner (Frühjahrslücke, neue Eingabe nötig).
 */
export function resolveLocal(local: string, zone: string): LocalResolution {
  if (!isKnownZone(zone)) return { kind: 'unknown-zone' };
  const m = LOCAL.exec(local);
  if (!m) return { kind: 'invalid' };
  const [y = 0, mo = 0, d = 0, h = 0, mi = 0, s = 0] = [
    m[1],
    m[2],
    m[3],
    m[4],
    m[5],
    m[6] ?? '0',
  ].map(Number);
  const naive = Date.UTC(y, mo - 1, d, h, mi, s);
  if (new Date(naive).getUTCMonth() !== mo - 1 || h > 23 || mi > 59 || s > 59)
    return { kind: 'invalid' };
  const candidates = new Set([
    offsetMinutesAt(naive - 86_400_000, zone),
    offsetMinutesAt(naive + 86_400_000, zone),
  ]);
  const found = new Map<number, ResolvedInstant>();
  for (const off of candidates) {
    const epochMs = naive - off * 60_000;
    if (offsetMinutesAt(epochMs, zone) === off)
      found.set(epochMs, { epochMs, offset: formatOffset(off) });
  }
  const all = [...found.values()].sort((a, b) => a.epochMs - b.epochMs);
  const [first, second] = all;
  if (!first) return { kind: 'gap' };
  if (!second) return { kind: 'unique', value: first };
  return { kind: 'ambiguous', options: all };
}

/** Lokaler Betriebstag (`YYYY-MM-DD`) in der Zone; `dayStartMinutes` verschiebt den Beginn (Standard Mitternacht). */
export function operatingDay(epochMs: number, zone: string, dayStartMinutes = 0): string {
  const p = partsAt(epochMs, zone);
  const shifted = new Date(asUtc(p) - dayStartMinutes * 60_000);
  return `${pad(shifted.getUTCFullYear(), 4)}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`;
}

/** Lesbare Ortszeit mit Offset, z. B. `25.10.2026, 02:30:00 (UTC+02:00)`. */
export function formatLocal(epochMs: number, zone: string): string {
  const p = partsAt(epochMs, zone);
  return `${pad(p.d)}.${pad(p.mo)}.${pad(p.y, 4)}, ${pad(p.h)}:${pad(p.mi)}:${pad(p.s)} (UTC${formatOffset(offsetMinutesAt(epochMs, zone))})`;
}
