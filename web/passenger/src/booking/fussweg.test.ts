import { describe, expect, it } from 'vitest';
import { fussweg, luftlinieMeter } from './fussweg';

describe('Fußweg als Luftlinie (WP-M2-41)', () => {
  it('luftlinie_und_gehzeit', () => {
    const a = { latitude: 51.05, longitude: 13.74 };
    expect(luftlinieMeter(a, a)).toBe(0);
    // 0,001 Grad Breite sind rund 111 m
    const d = luftlinieMeter(a, { latitude: 51.051, longitude: 13.74 });
    expect(d).toBeGreaterThanOrEqual(111);
    expect(d).toBeLessThanOrEqual(112);
    expect(fussweg(a, { latitude: 51.051, longitude: 13.74 })).toEqual({ meter: d, minuten: 2 });
    // nie 0 Minuten bei sehr kurzem Weg
    expect(fussweg(a, a).minuten).toBe(1);
  });
});
