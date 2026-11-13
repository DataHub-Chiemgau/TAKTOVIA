import type {
  Defect,
  DefectArea,
  DefectCategory,
  DefectEffect,
  DefectKind,
} from '@taktovia/api-client';

export type Status = Defect['status'];

/** Anzeigenamen der Kataloge (Detailvertrag `m3-schadenakte.md`, Planungsannahmen). */
export const CATEGORY_LABELS: Readonly<Record<DefectCategory, string>> = {
  BODY: 'Karosserie',
  GLASS: 'Glas',
  TIRES_WHEELS: 'Räder und Reifen',
  LIGHTS: 'Beleuchtung',
  BRAKES: 'Bremsen',
  DRIVETRAIN: 'Antrieb',
  STEERING_SUSPENSION: 'Lenkung und Fahrwerk',
  ELECTRICAL_HV: 'Elektrik und Hochvolt',
  INTERIOR: 'Innenraum',
  ACCESSIBILITY_EQUIPMENT: 'Barrierefreiheits-Ausstattung',
  SAFETY_EQUIPMENT: 'Sicherheitsausstattung',
  CLEANLINESS: 'Sauberkeit',
  OTHER: 'Sonstiges',
};
export const KIND_LABELS: Readonly<Record<DefectKind, string>> = {
  SCRATCH: 'Kratzer',
  DENT: 'Delle',
  CRACK_BREAK: 'Riss oder Bruch',
  MALFUNCTION: 'Funktionsstörung',
  MISSING_PART: 'Teil fehlt',
  LEAK: 'Undicht',
  WEAR: 'Verschleiß',
  CONTAMINATION: 'Verschmutzung',
  OTHER: 'Sonstiges',
};
export const AREA_LABELS: Readonly<Record<DefectArea, string>> = {
  FRONT: 'Front',
  REAR: 'Heck',
  LEFT_SIDE: 'Linke Seite',
  RIGHT_SIDE: 'Rechte Seite',
  ROOF: 'Dach',
  UNDERBODY: 'Unterboden',
  WHEELS_TIRES: 'Räder und Reifen',
  INTERIOR_PASSENGER: 'Fahrgastraum',
  INTERIOR_DRIVER: 'Fahrerplatz',
  LUGGAGE: 'Gepäckraum',
  WHEELCHAIR_AREA: 'Rollstuhlplatz',
  ENGINE_BATTERY: 'Antrieb und Batterie',
  OTHER: 'Sonstiges',
};
export const EFFECT_LABELS: Readonly<Record<DefectEffect, string>> = {
  NONE_VISIBLE: 'Keine sichtbare Auswirkung',
  COSMETIC_ONLY: 'Nur optisch',
  FUNCTION_LIMITED: 'Funktion eingeschränkt',
  NOT_ROADWORTHY: 'Nicht fahrbereit',
  FLUID_LEAK: 'Flüssigkeit tritt aus',
  WARNING_LIGHT: 'Warnleuchte',
  NOISE_VIBRATION: 'Geräusch oder Vibration',
  OTHER: 'Sonstiges',
};
export const STATUS_LABELS: Readonly<Record<Status, string>> = {
  NEW: 'neu',
  REVIEWED: 'geprüft',
  IN_PROGRESS: 'in Bearbeitung',
  REPAIR: 'Reparatur',
  CLOSED: 'abgeschlossen',
  DISMISSED: 'verworfen',
};

/** Anzeigenamen der Klassen (DMG-005); `SAFETY_CRITICAL` entspricht „S0“ aus PH-AT-020. */
export const SEVERITY_LABELS: Readonly<Record<NonNullable<Defect['severity']>, string>> = {
  SAFETY_CRITICAL: 'sicherheitskritisch',
  OPERATIONALLY_RELEVANT: 'einsatzrelevant',
  COSMETIC: 'kosmetisch',
  OTHER: 'sonstig',
};
