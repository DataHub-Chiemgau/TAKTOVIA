"""WP-M1-41 (LH-6.18-SEC-011): Prozessnachweis zum Sicherheitsvorfall.

Prüft Dokumente, nicht die Organisation: das Betriebsdokument nennt Eskalationsstufen, Verantwortliche, Untersuchungsschritte, die
Entscheidungsvorlage Meldung und trägt einen Freigabevermerk; die Protokollvorlage und jedes Übungsprotokoll enthalten die Pflichtfelder
Identifikationszeitpunkt, Eindämmungsmaßnahmen, Meldeentscheidung (im Protokoll nicht leer). Die Freigabe selbst bleibt organisatorisch (OP-32).
"""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BETRIEB = REPO / 'docs' / 'betrieb'
PFLICHTFELDER = ('Identifikationszeitpunkt', 'Eindämmungsmaßnahmen', 'Meldeentscheidung')


def abschnitte(text):
    """Überschrift der Ebene 2 -> Inhalt bis zur nächsten Überschrift der Ebene 2."""
    teile = re.split(r'^## +(.+)$', text, flags=re.M)
    return {teile[i].strip(): teile[i + 1].strip() for i in range(1, len(teile), 2)}


def inhalt_leer(inhalt):
    zeilen = [z for z in inhalt.splitlines() if z.strip()]
    return not zeilen or all(re.fullmatch(r'[:\s]*(Zeitpunkt[^|]*:|Je Maßnahme[^|]*\.|Entscheidung[^|]*:)\s*', z) for z in zeilen)


class SicherheitsvorfallProzess(unittest.TestCase):
    def test_betriebsdokument_hat_stufen_verantwortliche_untersuchungsschritte_und_freigabe(self):
        text = (BETRIEB / 'sicherheitsvorfall.md').read_text(encoding='utf-8')
        sec = abschnitte(text)
        for name in ('Freigabevermerk', 'Rollen und Verantwortliche', 'Eskalationsstufen', 'Untersuchungsschritte', 'Eindämmung', 'Entscheidungsvorlage Meldung'):
            self.assertIn(name, sec, f'Pflichtabschnitt fehlt: {name}')
            self.assertGreater(len(sec[name]), 80, f'Abschnitt leer: {name}')
        stufen = re.findall(r'^\| *([123]) ', sec['Eskalationsstufen'], flags=re.M)
        self.assertEqual(stufen, ['1', '2', '3'], 'drei Eskalationsstufen erwartet')
        self.assertGreaterEqual(len(re.findall(r'^\d+\. ', sec['Untersuchungsschritte'], flags=re.M)), 5)
        for rolle in ('Bereitschaft Betrieb', 'Sicherheitsverantwortliche', 'Datenschutzverantwortliche'):
            self.assertIn(rolle, sec['Rollen und Verantwortliche'])
        # Freigabevermerk: Feld vorhanden und der Status ehrlich benannt (Freigabe ist organisatorisch, OP-32)
        self.assertRegex(sec['Freigabevermerk'], r'Freigabestatus \| (\*\*)?(ausstehend|freigegeben)')
        self.assertIn('OP-32', text)
        for massnahme in ('Konto sperren', 'SUSPENDED', 'Secret rotieren'):
            self.assertIn(massnahme, sec['Eindämmung'])

    def test_uebungsprotokoll_enthaelt_identifikation_eindaemmung_meldeentscheidung(self):
        vorlage = abschnitte((BETRIEB / 'uebungsprotokoll-vorlage.md').read_text(encoding='utf-8'))
        for feld in PFLICHTFELDER:
            self.assertIn(feld, vorlage, f'Vorlage ohne Pflichtfeld {feld}')
        protokolle = sorted((BETRIEB / 'uebungen').glob('*.md'))
        self.assertTrue(protokolle, 'kein Übungsprotokoll vorhanden')
        for p in protokolle:
            sec = abschnitte(p.read_text(encoding='utf-8'))
            for feld in PFLICHTFELDER:
                self.assertIn(feld, sec, f'{p.name}: Pflichtfeld {feld} fehlt')
                self.assertFalse(inhalt_leer(sec[feld]), f'{p.name}: Pflichtfeld {feld} leer')
            self.assertRegex(sec['Identifikationszeitpunkt'], r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}', f'{p.name}: Zeitpunkt im ISO-Format')

    def test_leeres_protokoll_wird_erkannt(self):
        leer = abschnitte((BETRIEB / 'uebungsprotokoll-vorlage.md').read_text(encoding='utf-8'))
        for feld in PFLICHTFELDER:
            self.assertTrue(inhalt_leer(leer[feld]), f'Vorlage {feld} gilt nicht als leer')


if __name__ == '__main__':
    unittest.main()
