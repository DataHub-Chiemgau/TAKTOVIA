import { describe, expect, it } from 'vitest';
import { maskiereFehlerbericht, maskiereText } from './fehlerbericht';

const mail = 'erika.mustermann@example.test';

describe('Fehlerberichte ohne Kontaktdaten (NFR-015)', () => {
  it('maskiert Kontakt-, Zahlungs- und Tokenmuster, UUIDs bleiben', () => {
    const id = '5c1f2d7e-1111-4222-8333-9abcdef01234';
    const s = maskiereText(
      `Buchung ${id} für ${mail}, Tel +49 176 12345678, IBAN DE89 3704 0044 0532 0130 00, Karte 4111 1111 1111 1111, Musterstraße 12, 10115 Berlin, token=abc`,
    );
    expect(s).toContain(id);
    for (const w of [mail, '12345678', '3704 0044', '4111', 'Musterstraße', '10115', 'token=abc'])
      expect(s).not.toContain(w);
  });
  it('Bericht ohne Query, Stapel und Formularwerte', () => {
    const b = maskiereFehlerbericht(new Error(`Speichern fehlgeschlagen für ${mail}`), {
      route: `/konto?email=${mail}`,
      correlationId: '4bf92f35-77b3-4da6-a3ce-929d0e0e4736',
      appVersion: '1.2.3',
    });
    expect(JSON.stringify(b)).not.toContain('mustermann');
    expect(b.route).toBe('/konto');
    expect(b.correlationId).toBe('4bf92f35-77b3-4da6-a3ce-929d0e0e4736');
  });
});
