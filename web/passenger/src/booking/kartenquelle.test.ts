import { describe, expect, it } from 'vitest';
import { tileOriginOf, tileSourceFromTemplate } from './kartenquelle';

describe('Kartenquelle (Adapter)', () => {
  it('baut_kachel_urls_aus_der_vorlage', () => {
    const s = tileSourceFromTemplate('https://tiles.example.test/{z}/{x}/{y}.png', '© Quelle');
    expect(s?.tileUrl(12, 2200, 1370)).toBe('https://tiles.example.test/12/2200/1370.png');
    expect(s?.attribution).toBe('© Quelle');
  });

  it('ungueltige_oder_unsichere_vorlagen_ergeben_keine_quelle', () => {
    expect(tileSourceFromTemplate(undefined, 'x')).toBeNull();
    expect(tileSourceFromTemplate('https://t.example.test/{z}/{x}.png', 'x')).toBeNull();
    expect(tileSourceFromTemplate('http://t.example.test/{z}/{x}/{y}.png', 'x')).toBeNull();
    expect(tileSourceFromTemplate('https://t.example.test/{z}/{x}/{y}.png', undefined)).toBeNull();
    expect(tileSourceFromTemplate('nicht-url/{z}/{x}/{y}', 'x')).toBeNull();
  });

  it('herkunft_fuer_die_content_security_policy', () => {
    expect(tileOriginOf('https://tiles.example.test/{z}/{x}/{y}.png')).toBe(
      'https://tiles.example.test',
    );
    expect(tileOriginOf(undefined)).toBeUndefined();
    expect(tileOriginOf('kaputt')).toBeUndefined();
  });
});
