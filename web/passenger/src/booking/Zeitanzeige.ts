import { zeit } from '@taktovia/design-system';
import type { RideWindow } from '@taktovia/api-client';

/** `25.10.2026, 08:00 Uhr (UTC+01:00)` in der Zone des Dienstes. */
export function ortszeit(instant: string, zone: string): string {
  const ms = Date.parse(instant);
  if (Number.isNaN(ms)) return instant;
  const iso = zeit.toRfc3339(ms, zone);
  return `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}, ${iso.slice(11, 16)} Uhr (UTC${iso.slice(19)})`;
}

/** `25.10.2026, 08:00 bis 08:15 Uhr (UTC+01:00)`; geht das Fenster über Mitternacht, steht das Datum an beiden Enden. */
export function fenster(w: RideWindow, zone: string): string {
  const a = Date.parse(w.earliest_at);
  const b = Date.parse(w.latest_at);
  if (Number.isNaN(a) || Number.isNaN(b)) return `${w.earliest_at} bis ${w.latest_at}`;
  const x = zeit.toRfc3339(a, zone);
  const y = zeit.toRfc3339(b, zone);
  const day = (iso: string) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;
  const off = `(UTC${x.slice(19)}${x.slice(19) === y.slice(19) ? '' : ` bis UTC${y.slice(19)}`})`;
  if (day(x) === day(y)) return `${day(x)}, ${x.slice(11, 16)} bis ${y.slice(11, 16)} Uhr ${off}`;
  return `${day(x)}, ${x.slice(11, 16)} Uhr bis ${day(y)}, ${y.slice(11, 16)} Uhr ${off}`;
}
