import type { Money, SignedMoney } from '@taktovia/api-client';

/**
 * Darstellung eines vom Server gelieferten Betrags (WP-M2-41 AK 4): `amount_minor` wird nur als Zeichenkette zerlegt und mit dem Dezimalkomma versehen.
 * Es wird nie gerechnet, summiert oder gerundet; der Betrag der Anzeige ist exakt der Betrag des Angebots (`fare_quote`).
 */
/** Geschütztes Leerzeichen zwischen Betrag und Währung. */
const NBSP = String.fromCharCode(160);

export function formatMoney(m: Money | SignedMoney): string {
  const negative = m.amount_minor < 0;
  const digits = String(Math.abs(m.amount_minor)).padStart(m.exponent + 1, '0');
  const whole = digits.slice(0, digits.length - m.exponent).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const fraction = m.exponent > 0 ? `,${digits.slice(digits.length - m.exponent)}` : '';
  const symbol = m.currency === 'EUR' ? '€' : m.currency;
  return `${negative ? '−' : ''}${whole}${fraction}${NBSP}${symbol}`;
}
