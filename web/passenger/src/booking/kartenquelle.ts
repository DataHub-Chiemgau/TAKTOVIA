/**
 * Kartenkacheln über eine konfigurierbare Quelle (Adapter, WP-M2-40; Entscheidung D-09 zu Quelle und Lizenz ist offen und blockiert nur die Produktivschaltung).
 * Ohne Konfiguration gibt es keine Quelle: die Karte zeigt dann nur Bediengebiet und Haltepunkte ohne Hintergrund, die Liste bleibt gleichwertig.
 */
export interface TileSource {
  readonly name: string;
  /** Pflichtangabe der Quelle (Urheber/Lizenz), wird unter der Karte angezeigt. */
  readonly attribution: string;
  tileUrl(z: number, x: number, y: number): string;
}

/** Baut die Quelle aus einer URL-Vorlage mit `{z}`, `{x}`, `{y}` (nur `https:`); ungültige oder fehlende Vorlagen ergeben keine Quelle. */
export function tileSourceFromTemplate(
  template: string | undefined,
  attribution: string | undefined,
): TileSource | null {
  if (!template || !attribution) return null;
  if (!/\{z\}/.test(template) || !/\{x\}/.test(template) || !/\{y\}/.test(template)) return null;
  let origin: URL;
  try {
    origin = new URL(template.replace('{z}', '0').replace('{x}', '0').replace('{y}', '0'));
  } catch {
    return null;
  }
  if (origin.protocol !== 'https:') return null;
  return {
    name: origin.host,
    attribution,
    tileUrl: (z, x, y) =>
      template.replace('{z}', String(z)).replace('{x}', String(x)).replace('{y}', String(y)),
  };
}

/** Herkunft (Schema + Host) der Kachelquelle für `img-src` der Content-Security-Policy; `undefined` ohne gültige Vorlage. */
export function tileOriginOf(template: string | undefined): string | undefined {
  try {
    return template ? new URL(template.replace(/\{[zxy]\}/g, '0')).origin : undefined;
  } catch {
    return undefined;
  }
}
