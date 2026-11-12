/**
 * Fehlerberichte der Clients ohne Kontaktdaten (WP-M5-06, LH-6.19-NFR-015). Dieselben Regeln wie `Maskierung.kt`
 * (docs/betrieb/maskierung.md): Karte (Luhn), IBAN, E-Mail, Telefon, Adresse, Token, Boarding-Code werden ersetzt, UUIDs bleiben lesbar.
 * Ein Bericht enthält nur Meldungsart, maskierte Kurzmeldung, Route ohne Query, Correlation-ID und App-Version; nie Formularwerte, Header oder Stapel mit Daten.
 */
const UUID = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
type Regel = ReadonlyArray<readonly [string, RegExp]>;
// IBAN, E-Mail und Token vor der Kartenerkennung (IBAN-Ziffern können eine Luhn-Prüfsumme erfüllen), Telefon und Adresse danach.
const PRE: Regel = [
  ['token', /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}/g],
  ['token', /\b(?:bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}/gi],
  ['email', /[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g],
  ['iban', /\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){2,7}(?:\s?[A-Z0-9]{1,3})?\b/g],
  ['boarding-code', /\bBC-[A-Z0-9]{4,}\b/g],
];
const POST: Regel = [
  ['telefon', /(?<![\w.-])(?:\+|00)\d{1,3}[\s/().-]*\d(?:[\s/().-]?\d){5,13}(?![\w-])/g],
  ['telefon', /(?<![\w.+-])0\d{2,5}[\s/-]?\d(?:[\s/-]?\d){4,9}(?![\w-])/g],
  [
    'adresse',
    /\b[A-ZÄÖÜ][\wäöüß.-]*(?:stra(?:ß|ss)e|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)\s+\d{1,4}\s?[a-zA-Z]?\b(?:,?\s*\d{5}\s+[A-ZÄÖÜ][\wäöüß-]+)?/g,
  ],
  ['adresse', /(?<![\d.-])\d{5}\s+[A-ZÄÖÜ][a-zäöüß]{2,}(?:-[A-ZÄÖÜ][a-zäöüß]+)?\b/g],
];
const SECRET_KV =
  /\b(pass(?:word|wd)?|passwort|secret|token|access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|client[_-]?secret|cookie|session[_-]?id|otp|pin|cvc|cvv)\b(\s*[=:]\s*|"\s*:\s*")(?!\[maskiert)(?:Bearer\s+|Basic\s+)?[^\s,;&"'}\]]+/gi;
const BOARDING_KV =
  /\b(boarding[_ -]?code)\b(\s*(?:[=:]|"\s*:)\s*"?|\s+)(?!\[maskiert)[A-Za-z0-9-]{4,}/gi;
const CARD_RUN = /\d(?:[ .-]?\d){12,}/g;
const CARD_PREFIX =
  /^(34|37|4|51|52|53|54|55|36|300|301|302|303|304|305|35|6011|65|62|50|56|57|58|63|67)/;
const CARD_MASK = '[card-data-removed]';

function luhn(d: string): boolean {
  let sum = 0;
  let alt = false;
  for (let i = d.length - 1; i >= 0; i--) {
    let n = d.charCodeAt(i) - 48;
    if (alt) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
    alt = !alt;
  }
  return sum % 10 === 0;
}

function maskCards(text: string): string {
  if (text.length < 13) return text;
  const scan = text.replace(UUID, (m) => 'x'.repeat(m.length));
  const spans: Array<[number, number]> = [];
  for (const m of scan.matchAll(CARD_RUN)) {
    const idx: number[] = [];
    let digits = '';
    for (let i = m.index!; i < m.index! + m[0].length; i++)
      if (scan[i]! >= '0' && scan[i]! <= '9') {
        idx.push(i);
        digits += scan[i]!;
      }
    for (let len = 13; len <= Math.min(19, digits.length); len++)
      for (let st = 0; st + len <= digits.length; st++) {
        const part = digits.slice(st, st + len);
        if (CARD_PREFIX.test(part) && luhn(part)) spans.push([idx[st]!, idx[st + len - 1]! + 1]);
      }
  }
  spans.sort((a, b) => a[0] - b[0]);
  let out = '';
  let pos = 0;
  for (const [a, b] of spans) {
    if (a < pos) {
      pos = Math.max(pos, b);
      continue;
    }
    out += text.slice(pos, a) + CARD_MASK;
    pos = b;
  }
  return out + text.slice(pos);
}

/** Ersetzt Klartext der Feldklassen; UUIDs bleiben unverändert. */
export function maskiereText(text: string | null | undefined): string {
  if (!text) return '';
  let s = text.replace(SECRET_KV, (_m, k: string, sep: string) => `${k}${sep}[maskiert:token]`);
  s = s.replace(BOARDING_KV, (_m, k: string, sep: string) => `${k}${sep}[maskiert:boarding-code]`);
  s = phase(s, PRE);
  s = maskCards(s);
  return phase(s, POST);
}

function phase(s: string, regeln: Regel): string {
  const parts: string[] = [];
  let pos = 0;
  for (const m of s.matchAll(UUID)) {
    parts.push(segment(s.slice(pos, m.index), regeln), m[0]);
    pos = m.index + m[0].length;
  }
  parts.push(segment(s.slice(pos), regeln));
  return parts.join('');
}

function segment(seg: string, regeln: Regel): string {
  let s = seg;
  for (const [klasse, re] of regeln) s = s.replace(re, `[maskiert:${klasse}]`);
  return s;
}

export interface Fehlerbericht {
  art: string;
  meldung: string;
  route: string;
  correlationId?: string;
  appVersion?: string;
}

/** Route ohne Query und Fragment; Pfadsegmente mit Kontaktmustern werden maskiert. */
export function maskiereRoute(route: string): string {
  return maskiereText(route.split('?')[0]!.split('#')[0]!);
}

/**
 * Baut den versandfähigen Bericht. Nur diese Felder verlassen den Client; die Meldung ist maskiert und auf 300 Zeichen gekürzt,
 * ein Stapel wird nicht mitgeschickt.
 */
export function maskiereFehlerbericht(
  fehler: unknown,
  kontext: { route: string; correlationId?: string; appVersion?: string; art?: string },
): Fehlerbericht {
  const e = fehler instanceof Error ? fehler : new Error(String(fehler));
  const bericht: Fehlerbericht = {
    art: kontext.art ?? e.name,
    meldung: maskiereText(e.message).slice(0, 300),
    route: maskiereRoute(kontext.route),
  };
  if (kontext.correlationId && /^[0-9a-fA-F-]{36}$/.test(kontext.correlationId))
    bericht.correlationId = kontext.correlationId;
  if (kontext.appVersion && /^[A-Za-z0-9._+-]{1,32}$/.test(kontext.appVersion))
    bericht.appVersion = kontext.appVersion;
  return bericht;
}
