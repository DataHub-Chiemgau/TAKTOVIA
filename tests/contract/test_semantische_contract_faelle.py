"""Die zwölf semantischen Contract-Fälle aus Kap. 05 §5.8 (WP-M0-13).

Jeder Fall ist ein benannter, bis zum Zielmeilenstein übersprungener Test. Ein Fall kann nicht stillschweigend
gelöscht werden: test_zaehlpruefung erwartet genau zwölf. Mit dem jeweiligen Meilenstein wird der Skip durch die
echte Prüfung ersetzt (Zuordnung Kap. 08 §8.5, Umsetzungsplan §4).
"""
import unittest

FAELLE = {  # Kurzname: (Zielmeilenstein, Beschreibung, Referenz)
    'zwei_betreiber_letzter_platz': ('M2', 'Zwei Betreiber konkurrieren um den letzten Platz; höchstens eine Bestätigung', 'PH-AT-004'),
    'abgelaufener_hold': ('M2', 'Bestätigung gegen abgelaufenen Hold wird abgewiesen', 'PH-AT-010'),
    'sca_laenger_als_hold': ('M6', 'Zahlungsfreigabe (SCA) dauert länger als der Hold; kein Bestätigen abgelaufener Plätze', 'PH-AT-010'),
    'confirm_timeout_spaet_positiv': ('M6', 'Confirm-Timeout, Partner bestätigt später; genau eine Wirkung', 'PH-AT-007'),
    'storno_unbekannt': ('M6', 'Storno-Ergebnis unbekannt bleibt CANCEL_PENDING und wird abgeglichen', 'PH-AT-008'),
    'hundert_identische_webhooks': ('M1', '100 identische Webhooks erzeugen genau eine fachliche Wirkung', 'PH-AT-017'),
    'manipulation_tenant_id': ('M1', 'Manipulierte tenant_id/Mandantenheader liefert keine Fremddaten', 'PH-AT-001'),
    'unzulaessige_fremd_id': ('M1', 'Bekannte Fremd-UUID liefert dieselbe Antwort wie nicht vorhandene', 'PH-AT-001'),
    'eur_rundungsrest': ('M4', 'Centgenaue Umlage mit Rundungsrest (100,01 EUR auf drei Anteile)', 'PH-AT-013'),
    'geschlossene_periode': ('M4', 'Buchung in geschlossene Periode und späte Gutschrift', 'PH-AT-015'),
    'allokationssumme_ungleich_basis': ('M4', 'Allokationssumme ungleich Kostenbasis wird abgewiesen', 'PH-AT-013'),
    'offline_checkin_alte_planrevision': ('M3', 'Offline-Check-in auf veralteter Planrevision wird als Klärfall behandelt', 'PH-AT-017'),
}


def _skip(name):
    ms, text, ref = FAELLE[name]
    @unittest.skip(f'ab {ms}: {text} ({ref})')
    def test(self): pass
    test.__name__ = f'test_{name}'
    return test


class SemantischeContractFaelle(unittest.TestCase):
    def test_zaehlpruefung(self):
        self.assertEqual(len(FAELLE), 12)
        gesetzt = [n for n in dir(self) if n.startswith('test_') and n != 'test_zaehlpruefung']
        self.assertEqual(sorted(gesetzt), sorted(f'test_{n}' for n in FAELLE))


for _n in FAELLE:
    setattr(SemantischeContractFaelle, f'test_{_n}', _skip(_n))

if __name__ == '__main__':
    unittest.main()
