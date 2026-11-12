"""WP-M2-37 (LH-6.3-PAX-001): Das Nachweisdokument der Schrittzählung stimmt mit den Hauptschritten im Code der Passenger-PWA überein und bleibt unter der Obergrenze.

Grenze: Der Test fährt die Oberfläche NICHT; den Durchlauf der Standardfahrt belegen `StartView.test.tsx::standardfahrt_in_hoechstens_fuenf_schritten` (jsdom, Serverersatz)
und `StandardfahrtHttpApiTest` (echte Anwendung). Aufruf: python -m unittest discover -s tests/acceptance/passenger -p 'test_*.py'
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCHRITTE = ROOT / "web/passenger/src/booking/schritte.ts"
NACHWEIS = ROOT / "docs/umsetzungsplan/nachweise/WP-M2-37-schrittzaehlung.md"


def _code_steps():
    text = SCHRITTE.read_text(encoding="utf-8")
    block = text[text.index("HAUPTSCHRITTE = ["):text.index("] as const")]
    return re.findall(r"nr: (\d+), id: '(\w+)', label: '([^']+)'", block)


class SchrittzaehlungDokument(unittest.TestCase):
    def test_schrittzaehlung_dokument_entspricht_code_und_obergrenze(self):
        steps = _code_steps()
        limit = int(re.search(r"MAX_HAUPTSCHRITTE = (\d+)", SCHRITTE.read_text(encoding="utf-8")).group(1))
        self.assertEqual(limit, 5)
        self.assertEqual([int(n) for n, _, _ in steps], list(range(1, len(steps) + 1)))
        self.assertLessEqual(len(steps), limit)
        doc = NACHWEIS.read_text(encoding="utf-8")
        rows = re.findall(r"^\| (\d+) \| (.+?) \|", doc, flags=re.M)
        self.assertEqual(len(rows), len(steps), "Tabelle des Nachweises und Code haben gleich viele Hauptschritte")
        self.assertEqual([nr for nr, _, _ in steps], [dnr for dnr, _ in rows])
        self.assertIn(f"**{len(steps)} Hauptschritte**", doc)


if __name__ == "__main__":
    unittest.main()
