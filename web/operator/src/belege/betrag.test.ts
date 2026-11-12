import { describe, expect, it } from 'vitest';
import {
  HUNDRED_PERCENT,
  decimalString,
  formatMinor,
  parseDecimal,
  parseMoney,
  verteile,
} from './betrag';

const must = <T>(v: T | undefined): T => {
  if (v === undefined) throw new Error('Wert fehlt');
  return v;
};
const drei = (gewicht = 100000000n) => ['a', 'b', 'c'].map((id) => ({ id, gewicht }));

describe('UI-14 Geldrechnung ohne Gleitkomma', () => {
  it('drittelsplit_100_01_eur_ergibt_33_34_33_34_33_33', () => {
    const v = must(verteile(10001n, drei()));
    expect(v.betraege).toEqual([3334n, 3334n, 3333n]);
    expect(v.restcent).toBe(2);
    expect(v.restEmpfaenger).toEqual(['a', 'b']);
    expect(v.betraege.reduce((a, b) => a + b, 0n)).toBe(10001n);
    // 10.000 Minor Units: 3.334 / 3.333 / 3.333; die Korrektur über den Absolutbetrag hebt exakt auf
    expect(must(verteile(10000n, drei())).betraege).toEqual([3334n, 3333n, 3333n]);
    expect(must(verteile(-10000n, drei())).betraege).toEqual([-3334n, -3333n, -3333n]);
  });

  it('prozent_und_schluessel_summe_bleibt_exakt', () => {
    const p = must(parseDecimal('50'));
    const v = must(
      verteile(10001n, [
        { id: 'a', gewicht: p },
        { id: 'b', gewicht: must(parseDecimal('30')) },
        { id: 'c', gewicht: must(parseDecimal('20')) },
      ]),
    );
    expect(v.betraege).toEqual([5001n, 3000n, 2000n]);
    for (const basis of [1n, 2n, 7n, 99n, 10001n, 9007199254740991n])
      expect(must(verteile(basis, drei())).betraege.reduce((a, b) => a + b, 0n)).toBe(basis);
    expect(verteile(0n, [])).toBeUndefined();
    expect(verteile(100n, [{ id: 'a', gewicht: 0n }])).toBeUndefined();
  });

  it('gleichstand_nach_kleinerer_kennung_unabhaengig_von_der_reihenfolge', () => {
    const v = must(
      verteile(
        10000n,
        ['c', 'a', 'b'].map((id) => ({ id, gewicht: 100000000n })),
      ),
    );
    expect(v.betraege).toEqual([3333n, 3334n, 3333n]); // a erhält den Restcent
  });

  it('formatierung_und_eingabe_in_kleinster_einheit', () => {
    expect(formatMinor(10001n, 'EUR')).toBe('100,01 EUR');
    expect(formatMinor(123456789, 'EUR')).toBe('1.234.567,89 EUR');
    expect(formatMinor(-5n, 'EUR')).toBe('-0,05 EUR');
    expect(parseMoney('100,01')).toBe(10001n);
    expect(parseMoney('100.5')).toBe(10050n);
    expect(parseMoney('7')).toBe(700n);
    expect(parseMoney('1,234')).toBeUndefined();
    expect(parseMoney('abc')).toBeUndefined();
    expect(parseMoney('')).toBeUndefined();
    expect(parseDecimal('33,34')).toBe(3334000000n);
    expect(parseDecimal('33.123456789')).toBeUndefined();
    expect(decimalString(must(parseDecimal('33,3400')))).toBe('33.34');
    expect(decimalString(HUNDRED_PERCENT)).toBe('100');
  });
});
