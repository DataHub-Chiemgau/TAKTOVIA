/**
 * Rechnen mit Geld in der Belegmaske (WP-M4-38, UI-14): ausschließlich mit `bigint` in kleinster Einheit (nie Gleitkomma). Der Aufteilungsalgorithmus entspricht dem des Servers
 * (Largest Remainder, WP-M4-35): je Empfänger abrunden, Restcent nach größtem Dezimalrest, Gleichstand nach kleinerer Kennung (Textvergleich). Der Server rechnet immer selbst nach und ist maßgeblich;
 * die Maske zeigt Summenkontrolle und Rundungsrest nur vorab an. Als Exponent gilt 2 (EUR), solange die Maske keinen Währungskatalog kennt.
 */

const EXPONENT = 2;

/** `10001n` und `EUR` ergibt „100,01 EUR“; negative Beträge mit Minuszeichen. */
export function formatMinor(minor: bigint | number, currency: string): string {
  const v = BigInt(minor);
  const abs = v < 0n ? -v : v;
  const factor = 10n ** BigInt(EXPONENT);
  const whole = (abs / factor).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const frac = (abs % factor).toString().padStart(EXPONENT, '0');
  return `${v < 0n ? '-' : ''}${whole},${frac} ${currency}`;
}

/** „100,01“ oder „100.01“ oder „100“ in kleinste Einheit; höchstens zwei Nachkommastellen, sonst `undefined`. */
export function parseMoney(text: string): bigint | undefined {
  const t = text.trim().replace(/\s/g, '');
  if (!/^\d{1,13}([.,]\d{1,2})?$/.test(t)) return undefined;
  const [whole = '0', frac = ''] = t.split(/[.,]/);
  return BigInt(whole) * 100n + BigInt(frac.padEnd(EXPONENT, '0'));
}

const SCALE = 8;

/** Dezimalzahl (Komma oder Punkt, höchstens acht Nachkommastellen) als ganze Zahl mit 10^8 skaliert; sonst `undefined`. */
export function parseDecimal(text: string): bigint | undefined {
  const t = text.trim().replace(/\s/g, '');
  if (!/^\d{1,12}([.,]\d{1,8})?$/.test(t)) return undefined;
  const [whole = '0', frac = ''] = t.split(/[.,]/);
  return BigInt(whole) * 10n ** BigInt(SCALE) + BigInt(frac.padEnd(SCALE, '0'));
}

/** Skalierte Dezimalzahl als Zeichenfolge für die API (`33.34`), ohne überflüssige Nullen. */
export function decimalString(scaled: bigint): string {
  const factor = 10n ** BigInt(SCALE);
  const whole = scaled / factor;
  const frac = (scaled % factor).toString().padStart(SCALE, '0').replace(/0+$/, '');
  return frac ? `${whole}.${frac}` : `${whole}`;
}

export const HUNDRED_PERCENT = 100n * 10n ** BigInt(SCALE);

export interface Empfaenger {
  id: string;
  /** Gewicht, mit 10^8 skaliert (Prozent oder Schlüsselmenge), größer oder gleich 0. */
  gewicht: bigint;
}

export interface Verteilung {
  /** Betrag je Empfänger in Eingabereihenfolge, mit dem Vorzeichen der Basis. */
  betraege: bigint[];
  /** Anzahl der Cent, die über den Abrundungen hinaus verteilt wurden. */
  restcent: number;
  /** Kennungen der Empfänger, die einen Restcent erhalten. */
  restEmpfaenger: string[];
}

export function verteile(basisMinor: bigint, empfaenger: Empfaenger[]): Verteilung | undefined {
  const total = empfaenger.reduce((a, e) => a + e.gewicht, 0n);
  if (empfaenger.length === 0 || total <= 0n) return undefined;
  const abs = basisMinor < 0n ? -basisMinor : basisMinor;
  const floors = empfaenger.map((e) => (abs * e.gewicht) / total);
  const rests = empfaenger.map((e) => (abs * e.gewicht) % total);
  const restcent = Number(abs - floors.reduce((a, f) => a + f, 0n));
  const order = empfaenger
    .map((_, i) => i)
    .sort((a, b) => {
      const ra = rests[a] ?? 0n;
      const rb = rests[b] ?? 0n;
      if (ra !== rb) return ra > rb ? -1 : 1;
      const ia = empfaenger[a]?.id ?? '';
      const ib = empfaenger[b]?.id ?? '';
      return ia < ib ? -1 : ia > ib ? 1 : 0;
    });
  const extra = new Set(order.slice(0, restcent));
  const sign = basisMinor < 0n ? -1n : 1n;
  return {
    betraege: empfaenger.map((_, i) => sign * ((floors[i] ?? 0n) + (extra.has(i) ? 1n : 0n))),
    restcent,
    restEmpfaenger: order.slice(0, restcent).map((i) => empfaenger[i]?.id ?? ''),
  };
}
